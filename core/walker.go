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
	var rootDirs []nameRecord
	for _, root := range roots {
		// root 本身可能是文件
		info, err := os.Stat(root)
		if err == nil && !info.IsDir() {
			if !e.cancel.Load() {
				if incremental {
					e.recordSeen(root)
				}
				e.handleFile(root, info, incremental)
				e.files.Add(1)
			}
			continue
		}
		if err == nil {
			// 根目录自身也入目录名索引（旧实现由 addDir 在处理到该目录时补上）
			rootDirs = append(rootDirs, nameRecord{Path: root, Mtime: info.ModTime().UnixMilli()})
		}
		w.push(root)
	}
	e.dirs.AddBatch(rootDirs)
	w.run(e.workers)
	// 目录遍历结束后等待解析流水线排空：此刻不再有新任务入队，
	// run 返回即代表本轮全文解析全部完成，finishScan 的 RemoveExcept
	// 与快照落盘才能看到完整一致的全文库（取消时快速返回）。
	e.pp.run(parseWorkersFor(e.workers))
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

	var (
		filePaths []string
		fileInfos []fs.FileInfo
		subPaths  []string
		subMtimes []int64
	)
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
			// 子目录 mtime 在父目录 ReadDir 结果上取一次 lstat，
			// 不再像旧 addDir 那样处理到该目录时再 stat 一次。
			if info, err := child.Info(); err == nil {
				subPaths = append(subPaths, sub)
				subMtimes = append(subMtimes, info.ModTime().UnixMilli())
			}
			w.push(sub)
			continue
		}
		if strings.HasPrefix(name, ".") {
			continue
		}
		info, err := child.Info()
		if err != nil {
			continue // 竞态删除等瞬时错误只跳过该文件，不放弃整个目录
		}
		filePaths = append(filePaths, sub)
		fileInfos = append(fileInfos, info)
	}

	// 批量收录子目录 + 批量入文件名索引：每目录各一次锁获取，
	// 替代旧实现每条目 2~3 次（多协程下的锁竞争热点）。
	e.dirs.AddBatch(makeDirRecords(subPaths, subMtimes))
	e.processFileBatch(filePaths, fileInfos, w.incremental)
	if w.incremental {
		e.recordSeenBatch(filePaths)
	}
	if n := e.files.Add(int64(len(filePaths))); n%200 < int64(len(filePaths)) {
		e.notifyProgress("scan", n)
	}
}

// makeDirRecords 把子目录路径与 mtime 组装为目录索引的批量条目。
func makeDirRecords(paths []string, mtimes []int64) []nameRecord {
	if len(paths) == 0 {
		return nil
	}
	recs := make([]nameRecord, len(paths))
	for i := range paths {
		recs[i] = nameRecord{Path: paths[i], Mtime: mtimes[i]}
	}
	return recs
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
// 仅用于"扫描根本身是文件"的单文件入口；目录内文件走 processFileBatch。
func (e *Engine) handleFile(path string, info fs.FileInfo, incremental bool) {
	e.processFileBatch([]string{path}, []fs.FileInfo{info}, incremental)
}

// processFileBatch 批量处理一组文件（一个目录的直接子文件）：
// 增量模式先用一次读锁批量查重，size/mtime 均未变化的文件直接跳过
// （实现秒级增量重扫）；需要入索引的条目用一次写锁批量插入。
// 可解析文档不在此处同步解析（旧实现内联解析会让遍历协程被大文档
// 卡住），而是入队解析流水线（parsePool）由独立 worker 消费，
// 目录遍历与文档解析两条流水线并行，互不阻塞。
func (e *Engine) processFileBatch(paths []string, infos []fs.FileInfo, incremental bool) {
	if len(paths) == 0 {
		return
	}
	type addItem struct {
		path  string
		size  int64
		mtime int64
		parse bool // 是否为可解析文档（需要全文处理）
	}
	adds := make([]addItem, 0, len(paths))

	// 增量模式：一次读锁批量查重（旧实现逐文件 lookupPath，
	// 每个文件一次 RLock，多协程下是锁竞争热点）。
	var sizes, mtimes []int64
	var found []bool
	if incremental {
		sizes, mtimes, found = e.names.LookupBatch(paths)
	}

	for i, path := range paths {
		size := infos[i].Size()
		mtime := infos[i].ModTime().UnixMilli()
		ext := extOf(path)

		if incremental {
			if found[i] && sizes[i] == size && mtimes[i] == mtime {
				// 未变化：仅当名称在索引但全文缺失时补解析（快照不完整兜底）
				if docExts[ext] && !e.content.Has(path) {
					e.docsFound.Add(1)
					if size <= maxParseSize && size > 0 {
						e.pp.enqueue(path, ext)
					}
				}
				continue
			}
			if found[i] {
				e.updated.Add(1)
			} else {
				e.added.Add(1)
			}
		}

		adds = append(adds, addItem{path: path, size: size, mtime: mtime, parse: docExts[ext]})
	}
	if len(adds) == 0 {
		return
	}

	records := make([]nameRecord, len(adds))
	for i, it := range adds {
		records[i] = nameRecord{Path: it.path, Size: it.size, Mtime: it.mtime}
	}
	e.names.AddBatch(records)

	for _, it := range adds {
		if !it.parse {
			continue
		}
		e.docsFound.Add(1)
		if it.size > maxParseSize || it.size == 0 {
			continue
		}
		// 变更文件先移除旧全文，避免占双份容量；
		// 解析交给流水线（入队非阻塞，不拖慢遍历协程）。
		e.content.Remove(it.path)
		e.pp.enqueue(it.path, extOf(it.path))
	}
}

// parseAndStore 按扩展名选择解析通道（内置 OOXML / 宿主兜底 / 暂不支持），
// 成功抽取的正文写入全文库。alpha11 起由解析流水线 worker 调用，
// 不再在遍历协程上执行。
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
		// 文档解析阶段单独上报进度（phase="index"）：大文档解析期间
		// 文件计数会停顿，宿主据此显示"正在解析文档"而非卡住的扫描数。
		if n := e.docsIndexed.Add(1); n%5 == 0 {
			e.notifyProgress("index", n)
		}
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
