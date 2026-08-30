// Package core 是 AnythingMobile 的高性能搜索引擎内核。
//
// 通过 gomobile bind 编译为 AAR 供 Kotlin 外壳调用：
//
//      gomobile bind -target=android -javapkg=com.masgzy.anything ./core
//
// 依据 gobind 官方文档（golang.org/x/mobile/cmd/gobind）：
//   - 跨语言仅保证支持：整数/浮点/string/bool/[]byte/接口/结构体；
//     因此复合参数与返回值一律使用 JSON 字符串。
//   - 形如 NewT() *T 的函数会被转换为目标语言构造器。
//   - 方法名映射为 lowerCamelCase：StartScan -> startScan。
//   - panic 跨越语言边界会导致进程退出：所有宿主回调均已 recover 保护。
package core

import (
        "encoding/json"
        "errors"
        "runtime"
        "strings"
        "sync/atomic"
        "time"
)

// ProgressListener 由宿主（Kotlin）实现，接收扫描与索引进度。
// gobind 生成的 Java 接口方法名为 onProgress / onFinished / onError。
type ProgressListener interface {
        // OnProgress phase 取值 "scan" | "index"；done 为累计处理的文件数。
        OnProgress(phase string, done int64)
        // OnFinished 流程结束时回调一次，statsJSON 为 Stats 结构的 JSON。
        OnFinished(statsJSON string)
        // OnError 非致命错误（如个别目录无权限），不会中断整体流程。
        OnError(phase string, message string)
}

// ExternalParser 宿主侧解析器接口（如 Kotlin 层用 POI 兜底 .doc/.ppt/.xls）。
// 通过 SetExternalParser 注册；返回空串或错误时引擎跳过该文件。
type ExternalParser interface {
        ExtractText(absPath string) (string, error)
}

// Stats 一次扫描/索引的统计信息。
type Stats struct {
        Files       int64  `json:"files"`        // 已遍历文件总数
        DocsFound   int64  `json:"docs_found"`   // 可解析文档总数
        DocsIndexed int64  `json:"docs_indexed"` // 已建立全文索引的文档数
        DurationMS  int64  `json:"duration_ms"`
        Cancelled   bool   `json:"cancelled"`
        FinishedAt  string `json:"finished_at"`
}

// Engine 搜索引擎主对象。整个应用建议只持有一个实例。
type Engine struct {
        workers int

        cancel   atomic.Bool
        scanning atomic.Bool

        names   *NameIndex
        content *ContentStore

        listener   atomic.Value // ProgressListener
        extParser  atomic.Value // ExternalParser

        files       atomic.Int64
        docsFound   atomic.Int64
        docsIndexed atomic.Int64
        startAt     atomic.Int64 // unix milli
}

// NewEngine 创建引擎；workers<=0 时按 CPU 核数自动决定（上限 8）。
// Java 侧可用构造器 Engine(workers) 或 Core.newEngine(workers)。
func NewEngine(workers int) (*Engine, error) {
        if workers <= 0 {
                workers = runtime.NumCPU()
                if workers > 8 {
                        workers = 8
                }
        }
        if workers < 1 {
                workers = 1
        }
        return &Engine{
                workers: workers,
                names:   NewNameIndex(),
                content: NewContentStore(),
        }, nil
}

// SetListener 设置进度监听器，可在任意时刻替换。gobind: setListener
func (e *Engine) SetListener(l ProgressListener) {
        if l == nil {
                return
        }
        e.listener.Store(l)
}

// SetExternalParser 注册宿主解析器（Kotlin/POI 兜底旧版 Office 格式）。gobind: setExternalParser
func (e *Engine) SetExternalParser(p ExternalParser) {
        e.extParser.Store(p)
}

// StartScan 异步扫描并建立索引。rootsJSON 为路径数组的 JSON，例如：
//
//      ["/storage/emulated/0"]
//
// 重复调用返回错误；CancelScan 可取消。gobind: startScan(String)
func (e *Engine) StartScan(rootsJSON string) error {
        var roots []string
        if err := json.Unmarshal([]byte(rootsJSON), &roots); err != nil {
                return errors.New("core: rootsJSON 非法，应为 [\"/path\"] 形式的 JSON 数组")
        }
        if len(roots) == 0 {
                return errors.New("core: 扫描路径为空")
        }
        if !e.scanning.CompareAndSwap(false, true) {
                return errors.New("core: 扫描已在进行中")
        }
        e.cancel.Store(false)
        e.startAt.Store(time.Now().UnixMilli())

        go func() {
                rootsCopy := make([]string, len(roots))
                copy(rootsCopy, roots)
                cancelled := e.traverse(rootsCopy)

                st := Stats{
                        Files:       e.files.Load(),
                        DocsFound:   e.docsFound.Load(),
                        DocsIndexed: e.docsIndexed.Load(),
                        DurationMS:  time.Now().UnixMilli() - e.startAt.Load(),
                        Cancelled:   cancelled,
                        FinishedAt:  time.Now().Format(time.RFC3339),
                }
                b, err := json.Marshal(st)
                if err == nil {
                        e.notifyFinished(string(b))
                }
                e.scanning.Store(false)
        }()
        return nil
}

// CancelScan 请求取消当前扫描（异步生效）。gobind: cancelScan
func (e *Engine) CancelScan() { e.cancel.Store(true) }

// IsScanning 返回是否正在扫描。gobind: isScanning
func (e *Engine) IsScanning() bool { return e.scanning.Load() }

// Search 搜索文件名与文档全文，返回 SearchResponse 的 JSON。
// gobind: search(String, long) String
func (e *Engine) Search(query string, limit int64) (string, error) {
        q := strings.TrimSpace(query)
        if q == "" {
                return "", errors.New("core: 查询词为空")
        }
        if limit <= 0 || limit > 500 {
                limit = 100
        }
        start := time.Now()
        q = strings.TrimSpace(q)
        hits := e.names.Search(q, int(limit))
        if len(hits) < int(limit) {
                hits = append(hits, e.content.Search(q, int(limit)-len(hits))...)
        }
        resp := SearchResponse{
                Query:   q,
                Elapsed: time.Since(start).Milliseconds(),
                Total:   len(hits),
                Hits:    hits,
        }
        b, err := json.Marshal(resp)
        if err != nil {
                return "", err
        }
        return string(b), nil
}

// AddDocumentText 宿主把自行解析的文档正文喂给引擎（Kotlin/POI 兜底通道）。
// gobind: addDocumentText(String, String)
func (e *Engine) AddDocumentText(path, text string) error {
        if path == "" {
                return errors.New("core: path 为空")
        }
        if err := e.content.Add(path, text); err != nil {
                return err
        }
        e.docsIndexed.Add(1)
        return nil
}

// Stats 返回当前累计统计的 JSON。gobind: stats
func (e *Engine) Stats() string {
        st := Stats{
                Files:       e.files.Load(),
                DocsFound:   e.docsFound.Load(),
                DocsIndexed: e.docsIndexed.Load(),
                FinishedAt:  time.Now().Format(time.RFC3339),
        }
        if t := e.startAt.Load(); t > 0 && e.scanning.Load() {
                st.DurationMS = time.Now().UnixMilli() - t
        }
        b, _ := json.Marshal(st)
        return string(b)
}

// ---- 以下为内部通知工具：全部 recover 保护，防止 panic 跨语言边界 ----

func (e *Engine) notifyProgress(phase string, done int64) {
        if l, ok := e.listener.Load().(ProgressListener); ok && l != nil {
                safeCall(func() { l.OnProgress(phase, done) })
        }
}

func (e *Engine) notifyFinished(statsJSON string) {
        if l, ok := e.listener.Load().(ProgressListener); ok && l != nil {
                safeCall(func() { l.OnFinished(statsJSON) })
        }
}

func (e *Engine) notifyError(phase, msg string) {
        if l, ok := e.listener.Load().(ProgressListener); ok && l != nil {
                safeCall(func() { l.OnError(phase, msg) })
        }
}

func safeCall(f func()) {
        defer func() { _ = recover() }()
        f()
}

// SearchResponse Search 的返回结构。
type SearchResponse struct {
        Query   string    `json:"query"`
        Elapsed int64     `json:"elapsed_ms"`
        Total   int       `json:"total"`
        Hits    []FileHit `json:"hits"`
}

// FileHit 一条搜索结果。
type FileHit struct {
        Path    string `json:"path"`
        Name    string `json:"name"`
        Size    int64  `json:"size"`
        ModTime int64  `json:"mtime"`
        Matched string `json:"matched"` // "name" | "content"
        Snippet string `json:"snippet"` // 内容命中时的摘要，文件名命中为空
}
