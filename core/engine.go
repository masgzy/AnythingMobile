// Package core 是 AnythingMobile 的高性能搜索引擎内核。
//
// 通过 gomobile bind 编译为 AAR 供 Kotlin 外壳调用：
//
//	gomobile bind -target=android -javapkg=com.masgzy.anything ./core
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
	"fmt"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
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

// ScanOptions StartScan 的入参结构。
type ScanOptions struct {
	Roots []string `json:"roots"`
	// Mode "incremental"（默认）只处理有变动的文件并清理已删除条目；
	// "full" 清空索引后重建（对应"重建索引"功能）。
	Mode string `json:"mode"`
}

// Stats 一次扫描/索引的统计信息。
type Stats struct {
	Files       int64  `json:"files"`        // 本次遍历的文件总数
	Added       int64  `json:"added"`        // 新增入索引的文件数
	Updated     int64  `json:"updated"`      // 内容有变化而重新索引的文件数
	Removed     int64  `json:"removed"`      // 已消失（被删除）而移出索引的条目数
	DocsFound   int64  `json:"docs_found"`   // 可解析文档总数
	DocsIndexed int64  `json:"docs_indexed"` // 已建立全文索引的文档数
	DurationMS  int64  `json:"duration_ms"`
	Cancelled   bool   `json:"cancelled"`
	FirstBuild  bool   `json:"first_build"` // 本次是否为首次建索引（索引从空开始）
	FinishedAt  string `json:"finished_at"`
}

// Engine 搜索引擎主对象。整个应用建议只持有一个实例。
type Engine struct {
	workers int

	cancel   atomic.Bool
	scanning atomic.Bool

	names   *NameIndex // 文件名索引（kind=file）
	dirs    *NameIndex // 目录名索引（kind=folder）
	content *ContentStore

	// 磁盘持久化：dataDir 为空时不启用；restoreOnce 保证只恢复一次；
	// saveWG 用于串行化"上一次快照落盘"与"下一次扫描"。
	dataDir     string
	restoreOnce sync.Once
	saveWG      sync.WaitGroup

	listener  atomic.Value // ProgressListener
	extParser atomic.Value // ExternalParser

	files       atomic.Int64
	added       atomic.Int64
	updated     atomic.Int64
	removed     atomic.Int64
	docsFound   atomic.Int64
	docsIndexed atomic.Int64
	startAt     atomic.Int64 // unix milli

	// seenPaths 增量扫描期间记录"本次遍历见过的路径"。
	// 仅在 incremental 模式写入；字符串与索引内共享，开销可控。
	seenMu sync.Mutex
	seen   map[string]struct{}

	firstBuild bool // 本次扫描开始时索引是否为空
}

// NewEngine 创建引擎；workers<=0 时按 CPU 核数自动决定（上限 8）。
// dataDir 为索引快照目录（通常传应用 filesDir 下的子目录）：
// 构造时同步恢复已有索引，实现"打开即可搜索"；传空串则禁用持久化。
// Java 侧可用构造器 Engine(workers, dataDir) 或 Core.newEngine(workers, dataDir)。
func NewEngine(workers int, dataDir string) (*Engine, error) {
	if workers <= 0 {
		workers = runtime.NumCPU()
		if workers > 8 {
			workers = 8
		}
	}
	if workers < 1 {
		workers = 1
	}
	e := &Engine{
		workers: workers,
		names:   NewNameIndex("file"),
		dirs:    NewNameIndex("folder"),
		content: NewContentStore(),
		dataDir: dataDir,
	}
	e.restoreSnapshot() // 秒开的关键：构造即恢复上次索引
	return e, nil
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

// StartScan 异步扫描并更新索引。optionsJSON 为 ScanOptions 的 JSON：
//
//	{"roots":["/storage/emulated/0"],"mode":"incremental"}
//
// 兼容旧版纯数组格式（视为 full 模式）。重复调用返回错误；CancelScan 可取消。
// gobind: startScan(String)
// parseScanOptions 解析扫描入参 JSON，兼容旧版纯数组格式（视为 full 模式）。
func parseScanOptions(optionsJSON string) (ScanOptions, error) {
	var opt ScanOptions
	trimmed := strings.TrimSpace(optionsJSON)
	if strings.HasPrefix(trimmed, "[") {
		if err := json.Unmarshal([]byte(trimmed), &opt.Roots); err != nil {
			return opt, errors.New("core: optionsJSON 非法，应为 {\"roots\":[...]} 或 [\"/path\"]")
		}
		opt.Mode = "full"
		return opt, nil
	}
	if err := json.Unmarshal([]byte(trimmed), &opt); err != nil {
		return opt, errors.New("core: optionsJSON 非法，应为 {\"roots\":[...],\"mode\":\"...\"}")
	}
	return opt, nil
}

// beginScanCycle 准备一轮新扫描：复位计数器、判定首次建索引、
// 初始化增量 seen 表。调用方需先占用 scanning 标记。
func (e *Engine) beginScanCycle(mode string) {
	e.cancel.Store(false)
	e.startAt.Store(time.Now().UnixMilli())

	e.firstBuild = e.names.Count() == 0
	if mode == "full" {
		e.names.Reset()
		e.dirs.Reset()
		e.firstBuild = true
	}
	if mode == "incremental" {
		e.seenMu.Lock()
		e.seen = make(map[string]struct{}, e.names.Count()+1024)
		e.seenMu.Unlock()
	}
	e.files.Store(0)
	e.added.Store(0)
	e.updated.Store(0)
	e.removed.Store(0)
	e.docsFound.Store(0)
	e.docsIndexed.Store(0)
}

// finishScan 统一收尾：增量模式下移除"本次遍历未见到"的已删除条目、
// 通知统计并复位扫描状态，防止 UI 卡在"扫描中"；成功收尾后异步落盘
// 快照（失败不影响扫描结果）。
func (e *Engine) finishScan(mode string, cancelled bool) {
	if mode == "incremental" && !cancelled {
		e.seenMu.Lock()
		seen := e.seen
		e.seenMu.Unlock()
		removed := e.names.RemoveExcept(seen)
		e.dirs.RemoveExcept(seen)
		e.content.RemoveExcept(seen)
		e.removed.Store(int64(len(removed)))
	} else {
		e.seenMu.Lock()
		e.seen = nil
		e.seenMu.Unlock()
	}
	e.scanning.Store(false)
	// 先注册并启动异步落盘，再通知完成事件：
	// 保证宿主看到 OnFinished 时 saveWG 计数已包含本次落盘，
	// 测试/上层等待 saveWG 即可无竞态地确认写盘协程全部退出。
	if !cancelled && e.dataDir != "" {
		e.saveWG.Add(1)
		go func() {
			defer e.saveWG.Done()
			defer func() { _ = recover() }() // 落盘失败绝不拖垮进程
			_ = e.saveSnapshot()
		}()
	}
	if b, err := json.Marshal(e.currentStats(cancelled)); err == nil {
		e.notifyFinished(string(b))
	}
}

// currentStats 汇总当前计数器为 Stats。
func (e *Engine) currentStats(cancelled bool) Stats {
	return Stats{
		Files:       e.files.Load(),
		Added:       e.added.Load(),
		Updated:     e.updated.Load(),
		Removed:     e.removed.Load(),
		DocsFound:   e.docsFound.Load(),
		DocsIndexed: e.docsIndexed.Load(),
		DurationMS:  time.Now().UnixMilli() - e.startAt.Load(),
		Cancelled:   cancelled,
		FirstBuild:  e.firstBuild,
		FinishedAt:  time.Now().Format(time.RFC3339),
	}
}

// StartScan 异步扫描并更新索引。optionsJSON 为 ScanOptions 的 JSON：
//
//	{"roots":["/storage/emulated/0"],"mode":"incremental"}
//
// 兼容旧版纯数组格式（视为 full 模式）。重复调用返回错误；CancelScan 可取消。
// gobind: startScan(String)
func (e *Engine) StartScan(optionsJSON string) error {
	opt, err := parseScanOptions(optionsJSON)
	if err != nil {
		return err
	}
	if len(opt.Roots) == 0 {
		return errors.New("core: 扫描路径为空")
	}
	if opt.Mode != "full" && opt.Mode != "incremental" {
		opt.Mode = "incremental"
	}
	if !e.scanning.CompareAndSwap(false, true) {
		return errors.New("core: 扫描已在进行中")
	}
	// 等待上一次快照落盘完成，保证本次扫描期间无并发写盘，
	// 也避免"保存的是扫描进行到一半的状态"。
	e.saveWG.Wait()
	e.beginScanCycle(opt.Mode)

	go func() {
		// 鲁棒性：扫描在任意存储介质上运行，即使出现未预期异常，
		// 也必须 recover 并收尾 —— gobind 约束 panic 跨界即进程退出。
		defer func() {
			if r := recover(); r != nil {
				e.notifyError("scan", fmt.Sprintf("扫描发生内部错误: %v", r))
				e.finishScan(opt.Mode, true)
			}
		}()

		rootsCopy := make([]string, len(opt.Roots))
		copy(rootsCopy, opt.Roots)
		cancelled := e.traverse(rootsCopy, opt.Mode == "incremental")
		e.finishScan(opt.Mode, cancelled)
	}()
	return nil
}

// CancelScan 请求取消当前扫描（异步生效）。gobind: cancelScan
func (e *Engine) CancelScan() { e.cancel.Store(true) }

// IsScanning 返回是否正在扫描。gobind: isScanning
func (e *Engine) IsScanning() bool { return e.scanning.Load() }

// Search 搜索文件名、目录名与文档全文，返回 SearchResponse 的 JSON。
// 三类命中合并返回，宿主按 Kind / Matched 字段拆分到不同页签展示。
// gobind: search(String, long) String
func (e *Engine) Search(query string, limit int64) (string, error) {
	q := strings.TrimSpace(query)
	if q == "" {
		return "", errors.New("core: 查询词为空")
	}
	if limit <= 0 || limit > 1000 {
		limit = 300
	}
	start := time.Now()
	hits := e.names.Search(q, int(limit))
	// 目录命中按相同配额单独取，避免文件多时挤掉全部目录结果
	dirHits := e.dirs.Search(q, int(limit))
	hits = append(hits, dirHits...)
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

// RemovePaths 宿主删除文件后同步移出索引，pathsJSON 为路径数组 JSON。
// gobind: removePaths(String) String —— 返回实际移除的条数 JSON。
func (e *Engine) RemovePaths(pathsJSON string) (string, error) {
	var paths []string
	if err := json.Unmarshal([]byte(pathsJSON), &paths); err != nil {
		return "", errors.New("core: pathsJSON 非法，应为 [\"/path\"] 形式的 JSON 数组")
	}
	removed := 0
	for _, p := range paths {
		inNames := e.names.RemovePath(p)
		inDirs := e.dirs.RemovePath(p)
		inContent := e.content.Remove(p)
		if inNames || inDirs || inContent {
			removed++
		}
	}
	b, _ := json.Marshal(map[string]int{"removed": removed})
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
	st := e.currentStats(false)
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
	Kind    string `json:"kind"`    // "file" | "folder"
	Snippet string `json:"snippet"` // 内容命中时的摘要，名称命中为空
}

// recordSeen 增量模式下记录路径。锁粒度可控：路径数在十万级时
// 互斥锁开销仍远低于文件系统 IO。
func (e *Engine) recordSeen(path string) {
	e.seenMu.Lock()
	e.seen[path] = struct{}{}
	e.seenMu.Unlock()
}

// extOf 返回小写扩展名（含点）。
func extOf(path string) string {
	return strings.ToLower(filepath.Ext(path))
}
