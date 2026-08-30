package core

import (
        "fmt"
        "io/fs"
        "os"
        "path/filepath"
        "strings"
        "sync"
        "sync/atomic"
)

// maxParseSize 单个文档参与全文解析的大小上限（20MB）。
const maxParseSize = 20 << 20

// builtInParsable 引擎内置解析器支持的扩展名（OOXML 家族）。
var builtInParsable = map[string]bool{
        ".docx": true, ".pptx": true, ".xlsx": true,
}

// legacyDocExts 旧版二进制 Office 格式：优先交给宿主 ExternalParser 兜底。
var legacyDocExts = map[string]bool{
        ".doc": true, ".ppt": true, ".xls": true, ".wps": true,
}

// docExts 所有会被计入"文档"的扩展名（含 PDF，后续里程碑接入解析）。
var docExts = map[string]bool{
        ".docx": true, ".pptx": true, ".xlsx": true,
        ".doc": true, ".ppt": true, ".xls": true, ".wps": true,
        ".pdf": true,
}

// traverse 并发遍历 roots：每个 root 的第一层子目录作为一个独立子树，
// 由 workers 上限的 goroutine 池并行 WalkDir。返回是否被用户取消。
//
// incremental=true 时执行"进入应用自动增量重扫"：
//   - 名称与 size/mtime 均未变化的文件只计数，不重建索引、不重新解析文档，
//     因此未变动时整个过程只做 stat，秒级完成；
//   - 目录条目同步收集到目录名索引；
//   - 遍历结束后由 StartScan 的收尾逻辑移除已消失的条目。
//
// 依据 Android 官方文档，即使持有 MANAGE_EXTERNAL_STORAGE，
// 其他应用的 Android/{data,obb} 目录依然不可访问，因此直接跳过。
func (e *Engine) traverse(roots []string, incremental bool) bool {
        sem := make(chan struct{}, e.workers)
        var wg sync.WaitGroup
        cancelled := atomic.Bool{}

        for _, root := range roots {
                // root 本身可能是文件
                if info, err := os.Stat(root); err == nil && !info.IsDir() {
                        if !e.cancel.Load() {
                                if incremental {
                                        e.recordSeen(root)
                                }
                                e.handleFile(root, info, incremental)
                                e.files.Add(1)
                        }
                        continue
                }
                children, err := os.ReadDir(root)
                if err != nil {
                        e.notifyError("scan", "无法读取目录 "+root+": "+err.Error())
                        continue
                }
                if incremental {
                        e.recordSeen(root)
                }
                e.addDir(root)
                for _, child := range children {
                        if e.cancel.Load() {
                                break
                        }
                        sub := filepath.Join(root, child.Name())
                        if !child.IsDir() {
                                if info, err := child.Info(); err == nil && !strings.HasPrefix(child.Name(), ".") {
                                        if incremental {
                                                e.recordSeen(sub)
                                        }
                                        e.handleFile(sub, info, incremental)
                                        e.files.Add(1)
                                }
                                continue
                        }
                        sem <- struct{}{}
                        wg.Add(1)
                        go func(dir string) {
                                defer wg.Done()
                                defer func() { <-sem }()
                                e.walkSubtree(dir, &cancelled, incremental)
                        }(sub)
                }
        }
        wg.Wait()
        return cancelled.Load() || e.cancel.Load()
}

// walkSubtree 遍历单个子树。
func (e *Engine) walkSubtree(dir string, cancelled *atomic.Bool, incremental bool) {
        filepath.WalkDir(dir, func(path string, d fs.DirEntry, err error) error {
                if cancelled.Load() || e.cancel.Load() {
                        return fs.SkipAll
                }
                if err != nil {
                        // 无权限等情况：跳过该条目，不中断整体扫描
                        if d != nil && d.IsDir() {
                                return fs.SkipDir
                        }
                        return nil
                }
                name := d.Name()
                if d.IsDir() {
                        if filterDir(path, name) {
                                return fs.SkipDir
                        }
                        if incremental {
                                e.recordSeen(path)
                        }
                        e.addDir(path)
                        return nil
                }
                if strings.HasPrefix(name, ".") {
                        return nil
                }
                info, err := d.Info()
                if err != nil {
                        return nil
                }
                e.handleFile(path, info, incremental)
                if incremental {
                        e.recordSeen(path)
                }
                n := e.files.Add(1)
                if n%200 == 0 {
                        e.notifyProgress("scan", n)
                }
                return nil
        })
}

// addDir 将目录加入目录名索引（供"目录名"页签搜索）。
func (e *Engine) addDir(path string) {
        if info, err := os.Stat(path); err == nil {
                e.dirs.Add(path, 0, info.ModTime().UnixMilli())
        }
}

// filterDir 目录过滤规则；返回 true 表示跳过该目录。
func filterDir(path, name string) bool {
        if strings.HasPrefix(name, ".") {
                return true
        }
        switch name {
        case "LOST.DIR", "lost+found", "proc", "sys", "dev":
                return true
        case "data", "obb":
                // 跳过 Android/data 与 Android/obb（其他应用私有目录，无权访问）
                if filepath.Base(filepath.Dir(path)) == "Android" {
                        return true
                }
        }
        return false
}

// safeExtract 带 recover 保护的文档解析：个别损坏/异常文档只产生一条错误，
// 绝不拖垮整个扫描协程（gobind 约束：panic 跨语言边界即进程退出）。
func safeExtract(path string) (text string, err error) {
        defer func() {
                if r := recover(); r != nil {
                        text, err = "", fmt.Errorf("core: 文档解析异常: %v", r)
                }
        }()
        return ExtractText(path)
}

// handleFile 处理单个文件：入文件名索引；可解析文档尝试全文抽取。
// incremental=true 时，若索引中已有同路径且 size/mtime 均未变化，
// 则直接跳过（仅保留进度计数），实现秒级增量重扫。
func (e *Engine) handleFile(path string, info fs.FileInfo, incremental bool) {
        size := info.Size()
        mtime := info.ModTime().UnixMilli()

        if incremental {
                if oldSize, oldMtime, ok := e.names.LookupPath(path); ok {
                        if oldSize == size && oldMtime == mtime {
                                return // 未变化：跳过索引与文档解析
                        }
                        e.updated.Add(1)
                } else {
                        e.added.Add(1)
                }
        }

        e.names.Add(path, size, mtime)

        ext := extOf(path)
        if !docExts[ext] {
                return
        }
        e.docsFound.Add(1)
        if size > maxParseSize || size == 0 {
                return
        }

        // 增量模式中被标记 updated 的文件需要先移除旧全文，避免占双份容量
        e.content.Remove(path)

        var (
                text string
                err  error
        )
        switch {
        case builtInParsable[ext]:
                text, err = safeExtract(path)
        case legacyDocExts[ext]:
                if p, ok := e.extParser.Load().(ExternalParser); ok && p != nil {
                        text, err = e.parseExternal(path)
                }
        default:
                // .pdf 等：后续里程碑接入，当前仅计入 docsFound
                return
        }
        if err != nil || strings.TrimSpace(text) == "" {
                return
        }
        if e.content.Add(path, text) == nil {
                e.docsIndexed.Add(1)
        }
}

// parseExternal 调用宿主解析器，recover 保护 gobind 回调。
func (e *Engine) parseExternal(path string) (text string, err error) {
        defer func() {
                if r := recover(); r != nil {
                        text, err = "", fs.ErrInvalid
                }
        }()
        p, ok := e.extParser.Load().(ExternalParser)
        if !ok || p == nil {
                return "", fs.ErrInvalid
        }
        return p.ExtractText(path)
}
