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

// docExts 所有会被计入"文档"的扩展名（含 PDF，M3 里程碑接入解析）。
var docExts = map[string]bool{
        ".docx": true, ".pptx": true, ".xlsx": true,
        ".doc": true, ".ppt": true, ".xls": true, ".wps": true,
        ".pdf": true,
}

// traverse 并发遍历 roots：每个 root 的第一层子目录作为一个独立子树，
// 由 workers 上限的 goroutine 池并行 WalkDir。返回是否被用户取消。
//
// 依据 Android 官方文档，即使持有 MANAGE_EXTERNAL_STORAGE，
// 其他应用的 Android/{data,obb} 目录依然不可访问，因此直接跳过。
func (e *Engine) traverse(roots []string) bool {
        sem := make(chan struct{}, e.workers)
        var wg sync.WaitGroup
        cancelled := atomic.Bool{}

        for _, root := range roots {
                // root 本身可能是文件
                if info, err := os.Stat(root); err == nil && !info.IsDir() {
                        if !e.cancel.Load() {
                                e.handleFile(root, info)
                                e.files.Add(1)
                        }
                        continue
                }
                children, err := os.ReadDir(root)
                if err != nil {
                        e.notifyError("scan", "无法读取目录 "+root+": "+err.Error())
                        continue
                }
                for _, child := range children {
                        if e.cancel.Load() {
                                break
                        }
                        sub := filepath.Join(root, child.Name())
                        if !child.IsDir() {
                                if info, err := child.Info(); err == nil && !strings.HasPrefix(child.Name(), ".") {
                                        e.handleFile(sub, info)
                                        e.files.Add(1)
                                }
                                continue
                        }
                        sem <- struct{}{}
                        wg.Add(1)
                        go func(dir string) {
                                defer wg.Done()
                                defer func() { <-sem }()
                                e.walkSubtree(dir, &cancelled)
                        }(sub)
                }
        }
        wg.Wait()
        return cancelled.Load() || e.cancel.Load()
}

// walkSubtree 遍历单个子树。
func (e *Engine) walkSubtree(dir string, cancelled *atomic.Bool) {
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
                        return filterDir(path, name)
                }
                if strings.HasPrefix(name, ".") {
                        return nil
                }
                info, err := d.Info()
                if err != nil {
                        return nil
                }
                e.handleFile(path, info)
                n := e.files.Add(1)
                if n%200 == 0 {
                        e.notifyProgress("scan", n)
                }
                return nil
        })
}

// filterDir 目录过滤规则。
func filterDir(path, name string) error {
        if strings.HasPrefix(name, ".") {
                return fs.SkipDir
        }
        switch name {
        case "LOST.DIR", "lost+found", "proc", "sys", "dev":
                return fs.SkipDir
        case "data", "obb":
                // 跳过 Android/data 与 Android/obb（其他应用私有目录，无权访问）
                if filepath.Base(filepath.Dir(path)) == "Android" {
                        return fs.SkipDir
                }
        }
        return nil
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
func (e *Engine) handleFile(path string, info fs.FileInfo) {
        e.names.Add(path, info.Size(), info.ModTime().UnixMilli())

        ext := strings.ToLower(filepath.Ext(path))
        if !docExts[ext] {
                return
        }
        e.docsFound.Add(1)
        if info.Size() > maxParseSize || info.Size() == 0 {
                return
        }

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
                // .pdf 等：M3 里程碑接入，当前仅计入 docsFound
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
