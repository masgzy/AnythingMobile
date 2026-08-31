package core

import (
        "fmt"
        "io/fs"
        "os"
        "path/filepath"
        "strings"
        "sync"
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

// traverse 并发遍历 roots（v0.4：全深度并行）。
//
// 工作模型：共享目录队列 + workers 个工作协程。协程拾取目录后顺序处理
// 其条目（文件：入索引/增量比对；子目录：压回队列供其他协程拾取），
// 因此任意深度都保持 workers 路并行。移动存储经由 FUSE 访问，每次
// lstat 都有可观延迟 —— 旧实现只有根的第一层子树并行、子树内部串行，
// 大子树（如微信目录）独占单线程成为瓶颈；全深度并行可把该延迟
// 摊薄到每一路，"进入应用增量重扫一闪而过"由此达成。
//
// 终止条件：队列空且无在处理目录（全部完成），或被取消/内部错误。
//
// incremental=true 时执行"进入应用自动增量重扫"：
//   - 名称与 size/mtime 均未变化的文件只计数，不重建索引、不重新解析文档；
//   - 目录条目同步收集到目录名索引；
//   - 遍历结束后由 StartScan 的收尾逻辑移除已消失的条目。
//
// 依据 Android 官方文档，即使持有 MANAGE_EXTERNAL_STORAGE，
// 其他应用的 Android/{data,obb} 目录依然不可访问，因此直接跳过。
func (e *Engine) traverse(roots []string, incremental bool) bool {
        w := &dirWalker{e: e, incremental: incremental}
        w.cond = sync.NewCond(&w.mu)
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
                w.push(root)
        }
        w.run(e.workers)
        return e.cancel.Load()
}

// dirWalker 全深度并行遍历器：目录任务队列 + 固定worker池。
// pending = 队列中 + 正在处理的目录总数；pending 归零即遍历完成。
type dirWalker struct {
        e           *Engine
        incremental bool

        mu      sync.Mutex
        cond    *sync.Cond
        queue   []string
        pending int
        stopped bool // 取消或内部错误后，所有协程不再拾取新任务
}

// push 入队一个待遍历目录。
func (w *dirWalker) push(dir string) {
        w.mu.Lock()
        w.queue = append(w.queue, dir)
        w.pending++
        w.cond.Signal()
        w.mu.Unlock()
}

// stop 请求全部协程停止拾取（取消/内部错误时调用）。
func (w *dirWalker) stop() {
        w.mu.Lock()
        w.stopped = true
        w.cond.Broadcast()
        w.mu.Unlock()
}

// run 启动 worker 池并阻塞至遍历结束。
func (w *dirWalker) run(workers int) {
        if workers < 1 {
                workers = 1
        }
        var wg sync.WaitGroup
        for i := 0; i < workers; i++ {
                wg.Add(1)
                go func() {
                        defer wg.Done()
                        w.worker()
                }()
        }
        wg.Wait()
}

// worker 单个遍历协程：拾取目录 → 处理 → 计数，直到队列空且无在处理目录。
func (w *dirWalker) worker() {
        for {
                w.mu.Lock()
                for len(w.queue) == 0 && w.pending > 0 && !w.stopped {
                        w.cond.Wait()
                }
                if len(w.queue) == 0 || w.stopped {
                        w.mu.Unlock()
                        return
                }
                dir := w.queue[0]
                w.queue = w.queue[1:]
                w.mu.Unlock()

                if w.e.cancel.Load() {
                        w.stop()
                        return
                }

                // walkDir 的未预期异常只终结本次遍历并上报，
                // 绝不让 panic 存活（gobind 约束：panic 跨界即进程退出）。
                func() {
                        defer func() {
                                if r := recover(); r != nil {
                                        w.e.notifyError("scan", fmt.Sprintf("遍历发生内部错误: %v", r))
                                        w.stop()
                                }
                                w.mu.Lock()
                                w.pending--
                                if w.pending == 0 {
                                        w.cond.Broadcast()
                                }
                                w.mu.Unlock()
                        }()
                        w.walkDir(dir)
                }()
        }
}

// walkDir 遍历单个目录：文件入索引/增量比对，子目录压回共享队列。
func (w *dirWalker) walkDir(dir string) {
        entries, err := os.ReadDir(dir)
        if err != nil {
                // 无权限等情况：跳过该目录，不中断整体扫描
                w.e.notifyError("scan", "无法读取目录 "+dir+": "+err.Error())
                return
        }
        e := w.e
        if w.incremental {
                e.recordSeen(dir)
        }
        e.addDir(dir)
        for _, child := range entries {
                if e.cancel.Load() {
                        w.stop()
                        return
                }
                name := child.Name()
                sub := filepath.Join(dir, name)
                if child.IsDir() {
                        if filterDir(sub, name) {
                                continue
                        }
                        w.push(sub)
                        continue
                }
                if strings.HasPrefix(name, ".") {
                        continue
                }
                info, err := child.Info()
                if err != nil {
                        return
                }
                e.handleFile(sub, info, w.incremental)
                if w.incremental {
                        e.recordSeen(sub)
                }
                n := e.files.Add(1)
                if n%200 == 0 {
                        e.notifyProgress("scan", n)
                }
        }
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
// 特例：名称未变但全文缺失（如上次快照保存不完整）的文档会自动补解析，
// 保证名称索引与全文库最终一致。
func (e *Engine) handleFile(path string, info fs.FileInfo, incremental bool) {
        size := info.Size()
        mtime := info.ModTime().UnixMilli()
        ext := extOf(path)

        if incremental {
                oldSize, oldMtime, had := e.names.lookupPath(path)
                if had && oldSize == size && oldMtime == mtime {
                        if !docExts[ext] || e.content.Has(path) {
                                return // 未变化：跳过索引与文档解析
                        }
                        // 名称未变但全文缺失：仅补解析正文，不重复计数
                        e.docsFound.Add(1)
                        if size <= maxParseSize && size > 0 {
                                e.parseAndStore(path, ext)
                        }
                        return
                }
                if had {
                        e.updated.Add(1)
                } else {
                        e.added.Add(1)
                }
        }

        e.names.Add(path, size, mtime)

        if !docExts[ext] {
                return
        }
        e.docsFound.Add(1)
        if size > maxParseSize || size == 0 {
                return
        }

        // 变更文件先移除旧全文，避免占双份容量
        e.content.Remove(path)
        e.parseAndStore(path, ext)
}

// parseAndStore 按扩展名选择解析通道（内置 OOXML / 宿主兜底 / 暂不支持），
// 成功抽取的正文写入全文库。
func (e *Engine) parseAndStore(path, ext string) {
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
