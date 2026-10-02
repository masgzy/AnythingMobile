package core

// 文档解析流水线 —— 把"全文解析"从遍历协程中剥离出来。
//
// 为什么需要（alpha11 性能改造）：
//   旧实现 processFileBatch 在遍历协程内同步解析文档（开 zip + flate 解压
//   + XML 抽取，单个大文档可达数百毫秒）。遍历协程拾取到一个文档密集的
//   目录后会被解析卡住，不再继续目录遍历 —— 全量建索引时"走目录"与
//   "解析文档"两种负载互相拖累，移动硬盘/FUSE 的高 IO 延迟被放大。
//
// 工作模型：
//   遍历协程只负责收集元数据并入队解析任务（微秒级），解析由独立的
//   固定 worker 池消费。遍历与解析两条流水线并行，互不阻塞；
//   traverse 收尾时 run() 阻塞至队列排空，保证 finishScan 的
//   RemoveExcept 与快照落盘看到完整的全文库。
//
// 取消语义：引擎 cancel 置位后，worker 停止拾取并清空队列（在飞的
//   任务随 safeExtract 的 recover 自然结束），run() 快速返回。

import (
	"fmt"
	"sync"
)

// parseTask 一个待解析的文档任务。
type parseTask struct {
	path string
	ext  string
}

// parsePool 文档解析 worker 池。每次扫描新建一个实例（beginScanCycle），
// 生命周期与单轮扫描一致，不复用、无跨轮状态。
type parsePool struct {
	e *Engine

	mu      sync.Mutex
	cond    *sync.Cond
	queue   []parseTask
	pending int  // 队列中 + 在飞的任务总数；归零即排空
	stopped bool // 取消/收尾后不再拾取新任务
}

// parseWorkersFor 由遍历 worker 数推导解析 worker 数：
// 解析是 CPU+IO 双负载，取遍历数一半即可让两条流水线饱和而不至于
// 在低端机上同时跑满所有核（遍历多为 IO 等待，解析才吃 CPU）。
func parseWorkersFor(walkers int) int {
	n := (walkers + 1) / 2
	if n < 1 {
		n = 1
	}
	if n > 6 {
		n = 6
	}
	return n
}

func newParsePool(e *Engine) *parsePool {
	p := &parsePool{e: e}
	p.cond = sync.NewCond(&p.mu)
	return p
}

// enqueue 入队一个解析任务（由遍历协程调用，非阻塞）。
func (p *parsePool) enqueue(path, ext string) {
	p.mu.Lock()
	p.queue = append(p.queue, parseTask{path: path, ext: ext})
	p.pending++
	p.cond.Signal()
	p.mu.Unlock()
}

// stop 请求全部解析 worker 停止拾取并清空队列（取消扫描时调用）。
func (p *parsePool) stop() {
	p.mu.Lock()
	p.stopped = true
	p.queue = nil
	p.cond.Broadcast()
	p.mu.Unlock()
}

// run 启动解析 worker 池并阻塞至队列排空（或被取消）。
// 由 traverse 在目录遍历结束后调用：此刻不再有新任务入队，
// run 返回即代表本轮扫描的全文解析全部完成。
func (p *parsePool) run(workers int) {
	if workers < 1 {
		workers = 1
	}
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			p.worker()
		}()
	}
	wg.Wait()
}

// worker 单个解析协程：拾取任务 → 抽取正文（攒批）→ 批量入全文库，
// 直到队列排空或被取消。
//
// 攒批（alpha16）：worker 本地攒满 32 条或队列暂时排空时一次性
// AddBatch —— bleve 每次提交都产生 scorch 段生成/合并，逐文档提交
// 在数千文档的全量建索引下开销被放大；抽取仍是逐文档，只有入库
// 被批量化。取消/收尾路径同样 flush，保证已抽取正文不丢。
func (p *parsePool) worker() {
	const flushAt = 32
	buf := make([]DocText, 0, flushAt)
	flush := func() {
		if len(buf) == 0 {
			return
		}
		if n := p.e.content.AddBatch(buf); n > 0 {
			total := p.e.docsIndexed.Add(int64(n))
			p.e.notifyProgress("index", total)
		}
		buf = buf[:0]
	}
	for {
		p.mu.Lock()
		for len(p.queue) == 0 && p.pending > 0 && !p.stopped {
			p.cond.Wait()
		}
		if len(p.queue) == 0 || p.stopped {
			p.mu.Unlock()
			flush()
			return
		}
		task := p.queue[0]
		p.queue = p.queue[1:]
		p.mu.Unlock()

		if p.e.cancel.Load() {
			p.stop()
			flush()
			return
		}

		// 与遍历协程同样的铁律：panic 绝不跨 goroutine 存活
		//（gobind 约束：panic 跨语言边界即进程退出）。
		func() {
			defer func() {
				if r := recover(); r != nil {
					p.e.notifyError("index", fmt.Sprintf("文档解析发生内部错误: %v", r))
					p.stop()
				}
				p.mu.Lock()
				p.pending--
				if p.pending == 0 {
					p.cond.Broadcast()
				}
				p.mu.Unlock()
			}()
			if text, ok := p.e.extractDoc(task.path, task.ext); ok {
				buf = append(buf, DocText{Path: task.path, Text: text})
			}
		}()
		if len(buf) >= flushAt {
			flush()
		} else {
			// 队列暂时排空时顺手 flush：大文档在队尾堆积时降低不可见窗口
			p.mu.Lock()
			empty := len(p.queue) == 0
			p.mu.Unlock()
			if empty {
				flush()
			}
		}
	}
}
