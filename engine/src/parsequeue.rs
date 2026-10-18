//! 文档解析流水线 —— 移植 Go `core/parsequeue.go`。
//!
//! 为什么需要（alpha11 性能改造，语义照搬）：
//!   旧实现在遍历线程内同步解析文档（开 zip + 解压 + XML 抽取，单个大
//!   文档可达数百毫秒），遍历线程被解析卡住后不再继续目录遍历 —— 全量
//!   建索引时"走目录"与"解析文档"两种负载互相拖累。
//!
//! 工作模型：
//!   遍历线程只负责收集元数据并入队解析任务（微秒级），解析由独立的
//!   固定 worker 池消费。遍历与解析两条流水线并行，互不阻塞；
//!   traverse 收尾时 run() 阻塞至队列排空，保证 finishScan 的
//!   RemoveExcept 与快照落盘看到完整的全文库。
//!
//! 取消语义：引擎 cancel 置位后，worker 停止拾取并清空队列（在飞的
//!   任务随解析器自身的容错自然结束），run() 快速返回。

use std::collections::VecDeque;
use std::sync::{Arc, Condvar, Mutex, Weak};

use crate::engine::EngineImpl;

/// 解析 worker 本地攒批阈值（Go flushAt 同款：32 条一批提交）。
const FLUSH_AT: usize = 32;

/// 单文档解析超时（alpha21）：解析库对个别畸形文件可能死循环或长时间
/// 阻塞（PDF 畸形交叉引用表、嵌套异常的 OOXML 等），catch_unwind 防
/// 得住 panic 防不住挂起 —— 一个挂死的任务会让 pool.run 永不返回，
/// 宿主遮罩永久卡死（alpha20 真机反馈的最可能根因）。超时后放弃该
/// 文档继续流水线；被遗弃的辅助线程随解析自身结束或残留（个位数，
/// 扫描结束不影响进程）。
const PARSE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(45);

/// 一个待解析的文档任务。
#[derive(Debug, Clone)]
pub(crate) struct ParseTask {
    pub path: String,
    pub ext: String,
}

#[derive(Default)]
struct PoolState {
    queue: VecDeque<ParseTask>,
    /// 队列中 + 在飞的任务总数；归零即排空。
    pending: usize,
    /// 取消/收尾后不再拾取新任务。
    stopped: bool,
}

/// 文档解析 worker 池。每次扫描新建一个实例（run_scan 内），
/// 生命周期与单轮扫描一致，不复用、无跨轮状态。
/// 持 Weak 引用避免 Engine↔Pool 循环强引用（引擎实例常驻进程）。
pub(crate) struct ParsePool {
    engine: Weak<EngineImpl>,
    state: Mutex<PoolState>,
    cv: Condvar,
}

/// 由遍历 worker 数推导解析 worker 数（Go `parseWorkersFor` 同款）：
/// 解析是 CPU+IO 双负载，取遍历数一半即可让两条流水线饱和而不至于
/// 在低端机上同时跑满所有核。
pub(crate) fn parse_workers_for(walkers: usize) -> usize {
    walkers.div_ceil(2).clamp(1, 6)
}

impl ParsePool {
    pub(crate) fn new(engine: &Arc<EngineImpl>) -> Self {
        ParsePool {
            engine: Arc::downgrade(engine),
            state: Mutex::new(PoolState::default()),
            cv: Condvar::new(),
        }
    }

    /// 入队一个解析任务（由遍历线程调用，非阻塞）。
    pub(crate) fn enqueue(&self, path: &str, ext: &str) {
        if let Ok(mut s) = self.state.lock() {
            s.queue.push_back(ParseTask {
                path: path.to_string(),
                ext: ext.to_string(),
            });
            s.pending += 1;
            self.cv.notify_one();
        }
    }

    /// 请求全部解析 worker 停止拾取并清空队列（取消扫描时调用）。
    pub(crate) fn stop(&self) {
        if let Ok(mut s) = self.state.lock() {
            s.stopped = true;
            s.queue.clear();
            self.cv.notify_all();
        }
    }

    /// 启动解析 worker 池并阻塞至队列排空（或被取消）。
    /// 由 traverse 在目录遍历结束后调用：此刻不再有新任务入队，
    /// run 返回即代表本轮扫描的全文解析全部完成。
    pub(crate) fn run(self: &Arc<Self>, workers: usize) {
        let workers = workers.max(1);
        std::thread::scope(|scope| {
            for _ in 0..workers {
                let pool = Arc::clone(self);
                scope.spawn(move || pool.worker());
            }
        });
    }

    /// 单个解析线程：拾取任务 → 抽取正文（攒批）→ 批量入全文库，
    /// 直到队列排空或被取消。
    ///
    /// 攒批（Go alpha16 同款）：worker 本地攒满 32 条或队列暂时排空时
    /// 一次性 AddBatch —— 每次提交都产生段生成/合并，逐文档提交在数千
    /// 文档的全量建索引下开销被放大；抽取仍是逐文档，只有入库被批量化。
    /// 取消/收尾路径同样 flush，保证已抽取正文不丢。
    fn worker(self: &Arc<Self>) {
        let Some(engine) = self.engine.upgrade() else {
            return;
        };
        let mut buf: Vec<(String, String)> = Vec::with_capacity(FLUSH_AT);
        loop {
            let task = {
                let mut s = match self.state.lock() {
                    Ok(s) => s,
                    Err(_) => return,
                };
                while s.queue.is_empty() && s.pending > 0 && !s.stopped {
                    s = match self.cv.wait(s) {
                        Ok(s) => s,
                        Err(_) => return,
                    };
                }
                if s.queue.is_empty() || s.stopped {
                    break; // 锁随作用域释放
                }
                s.queue.pop_front().unwrap()
                // pending 不在此减：在飞任务仍计入，其他 worker 据此等待
            };

            if engine.is_cancelled() {
                self.stop();
                break;
            }

            // 铁律：解析全路径容错 —— catch_unwind 防 panic，
            // 超时防挂起（alpha21），最坏损失单个任务。
            // None（失败/超时/panic）仅跳过该文档，不再 stop() 整条流水线
            //（旧实现一个坏文档会拖垮整轮索引）。
            let text = run_parse_with_timeout(&engine, &task, PARSE_TIMEOUT);
            if let Some(text) = text {
                buf.push((task.path.clone(), text));
            }

            // 任务完成：pending 减一，归零唤醒等待者
            if let Ok(mut s) = self.state.lock() {
                s.pending = s.pending.saturating_sub(1);
                if s.pending == 0 {
                    self.cv.notify_all();
                }
            }

            if buf.len() >= FLUSH_AT {
                self.flush(&engine, &mut buf);
            } else if let Ok(s) = self.state.lock() {
                // 队列暂时排空时顺手 flush：大文档在队尾堆积时降低不可见窗口
                if s.queue.is_empty() {
                    drop(s);
                    self.flush(&engine, &mut buf);
                }
            }
        }
        self.flush(&engine, &mut buf);
    }

    /// 攒批入库：成功条数计入 docs_indexed 并按 phase="index" 上报进度
    /// （大文档解析期间文件计数会停顿，宿主据此显示"正在解析文档"）。
    fn flush(&self, engine: &Arc<EngineImpl>, buf: &mut Vec<(String, String)>) {
        if buf.is_empty() {
            return;
        }
        let docs = std::mem::take(buf);
        let n = engine.content_add_batch(&docs);
        if n > 0 {
            let total = engine.docs_indexed_add(n);
            engine.notify_progress("index", total);
        }
    }
}

/// 单文档解析（带超时兑底）：在独立辅助线程执行，worker 最多等
/// `timeout`。panic / 失败 / 超时统一返回 None，worker 继续消费队列。
fn run_parse_with_timeout(
    engine: &Arc<EngineImpl>,
    task: &ParseTask,
    timeout: std::time::Duration,
) -> Option<String> {
    let eng = Arc::clone(engine);
    let path = task.path.clone();
    let ext = task.ext.clone();
    let (tx, rx) = std::sync::mpsc::channel();
    let spawned = std::thread::Builder::new()
        .name("anything-parse-one".to_string())
        .spawn(move || {
            let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                eng.extract_doc_path(&path, &ext)
            }));
            let _ = tx.send(r.ok().flatten());
        });
    if spawned.is_err() {
        return None; // 线程创建失败：跳过该文档
    }
    match rx.recv_timeout(timeout) {
        Ok(text) => text,
        Err(_) => {
            engine.notify_error("index", "core: 单文档解析超时，已跳过");
            None
        }
    }
}

// ---------------------------------------------------------------- 测试

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_workers_for_bounds() {
        assert_eq!(parse_workers_for(0), 1);
        assert_eq!(parse_workers_for(1), 1);
        assert_eq!(parse_workers_for(4), 2);
        assert_eq!(parse_workers_for(16), 6);
    }

    #[test]
    fn enqueue_stop_and_drain() {
        let engine = crate::engine::EngineImpl::new_for_test();
        let pool = Arc::new(ParsePool::new(&engine));
        for i in 0..10 {
            pool.enqueue(&format!("/x/f{i}.txt"), ".txt");
        }
        assert_eq!(pool.state.lock().unwrap().pending, 10);
        pool.stop();
        assert!(pool.state.lock().unwrap().queue.is_empty());
        assert!(pool.state.lock().unwrap().stopped);
        // run 立即返回（队列已清空）
        pool.run(2);
    }

    #[test]
    fn pending_reaches_zero_after_run() {
        let tmp = tempfile::tempdir().unwrap();
        // 一个真实 docx，走完整解析→入库链路
        let p = tmp.path().join("t.docx");
        {
            use std::io::Write;
            let f = std::fs::File::create(&p).unwrap();
            let mut zw = zip::ZipWriter::new(f);
            zw.start_file(
                "word/document.xml",
                zip::write::SimpleFileOptions::default(),
            )
            .unwrap();
            zw.write_all(b"<w:p><w:t>pool test content</w:t></w:p>")
                .unwrap();
            zw.finish().unwrap();
        }
        let engine = crate::engine::EngineImpl::new_for_test();
        let pool = Arc::new(ParsePool::new(&engine));
        pool.enqueue(p.to_str().unwrap(), ".docx");
        pool.run(1);
        assert_eq!(pool.state.lock().unwrap().pending, 0);
        assert!(pool.state.lock().unwrap().queue.is_empty());
    }

    #[test]
    fn cancelled_engine_stops_pool() {
        let engine = crate::engine::EngineImpl::new_for_test();
        engine.cancel_scan();
        assert!(engine.is_cancelled());
        let pool = Arc::new(ParsePool::new(&engine));
        pool.enqueue("/x/f.docx", ".docx");
        pool.run(1);
        // cancel 置位：任务被丢弃
        assert_eq!(engine.stats_indexed_docs(), 0);
    }
}
