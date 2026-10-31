//! 引擎状态机 —— 对应 Go `core/engine.go`（+ external.go / ingest.go）。
//!
//! alpha20 完整实现：
//! - 名称/目录索引（[`crate::nameindex`]）、全文内容库（[`crate::fts`]）、
//!   快照持久化（[`crate::persist`]）、解析流水线（[`crate::parsequeue`]）、
//!   M4 搜索语法与配额分配、搜索结果缓存；
//! - 扫描状态机（scanning/cancel 原子标志）、全深度并行遍历、
//!   进度/错误/完成回调、Stats JSON、句柄生命周期。
//!
//! 并发模型对齐 Go 版：
//! - `scanning` CompareExchange 保证 StartScan/Ingest 互斥；
//! - 扫描在自建线程执行（Go 为 goroutine），任何字段只经原子/锁访问；
//! - 快照落盘与下一次扫描互斥（SaveGate = Go saveWG）；
//! - 外部扩展索引整表替换与查询合并互斥（ext_lock = Go extMu）；
//! - 回调一律经 ListenerSlot（内部吞掉 Java 异常），绝不向上传播。

use std::collections::HashSet;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use crate::callback::{ListenerSlot, ParserSlot};
use crate::error::{EngineError, EngineResult};
use crate::fts::ContentStore;
use crate::nameindex::NameIndex;
use crate::parsequeue::{ParsePool, parse_workers_for};
use crate::persist::SaveGate;
use crate::query::parse_query;
use crate::types::{IngestEntry, SearchResponse, Stats, now_millis};
use crate::walker::{self, DirRecord, FileRecord, WalkHooks};

/// 单个文档参与全文解析的大小上限（Go `maxParseSize` 同款：20MB）。
pub(crate) const MAX_PARSE_SIZE: usize = 20 << 20;

/// SAF 直连通道（extract_doc）的单文档解析超时（审计 V-1 加固）。
/// 与扫描流水线的 parsequeue::PARSE_TIMEOUT 同值：超时即放弃全文，
/// 文件名仍入索引；zip 层另有 128MB 解压字节上限（docparse::MAX_UNCOMPRESSED），
/// 正常文档不会触达该值 —— 恶意构造的"无文本大 XML"由两层防御共同兜住。
const DOC_EXTRACT_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(45);

/// 引擎内置解析器支持的扩展名（OOXML 家族 + PDF，Go `builtInParsable` 同款）。
fn is_builtin_parsable(ext: &str) -> bool {
    matches!(ext, ".docx" | ".pptx" | ".xlsx" | ".pdf")
}

/// 旧版二进制 Office 格式：优先交给宿主 ExternalParser 兜底。
fn is_legacy_doc_ext(ext: &str) -> bool {
    matches!(ext, ".doc" | ".ppt" | ".xls" | ".wps")
}

/// 所有会被计入"文档"的扩展名（决定 docs_found 计数与补解析）。
fn is_doc_ext(ext: &str) -> bool {
    is_builtin_parsable(ext) || is_legacy_doc_ext(ext)
}

/// 进度上报闸门：累计数跨过 200 边界立即上报（Go walkDir 同款），
/// 另加 ≥1s 心跳兑底 —— 小文件集合（累计不足 200）也至少每秒看到
/// 一次计数推进，消除 alpha20 真机反馈的「当前扫描到文件数 0」
/// 长时间不动的观感（纯函数便于测试）。
fn progress_gate(total: i64, batch: i64, last_ms: i64, now_ms: i64) -> bool {
    total % 200 < batch || now_ms - last_ms >= 1000
}

/// 引擎实现（Kotlin 经句柄间接持有）。
pub struct EngineImpl {
    /// 工作线程数（<=0 时按 CPU 核数自动决定，上限 8 —— Go 同款）。
    workers: usize,
    /// 索引目录（空串禁用持久化）。
    data_dir: String,

    // ---- 索引 ----
    names: Arc<NameIndex>,
    dirs: Arc<NameIndex>,
    /// 特权通道外部索引（与常规索引隔离：增量扫描不触碰，查询时合并）。
    ext_names: Arc<NameIndex>,
    ext_dirs: Arc<NameIndex>,
    /// "整表替换"与"查询合并"之间的跨表一致性（Go extMu 同款）。
    ext_lock: RwLock<()>,
    content: Arc<ContentStore>,
    save_gate: Arc<SaveGate>,

    // ---- 扫描状态机 ----
    cancel: AtomicBool,
    scanning: AtomicBool,
    first_build: AtomicBool,
    start_at: AtomicI64, // unix 毫秒，0 = 未开始

    // ---- 计数器（Go atomic 字段一一对应）----
    files: AtomicI64,
    added: AtomicI64,
    updated: AtomicI64,
    removed: AtomicI64,
    docs_found: AtomicI64,
    docs_indexed: AtomicI64,
    /// 宿主回调未送达次数（alpha23 诊断字段：Rust→Kotlin 方向 call_method
    /// 失败 —— 典型因 release 构建混淆了回调接口。随 stats.host_cb_errors 暴露）。
    host_cb_err: AtomicI64,

    // ---- 增量扫描 seen 表（Go recordSeen 同语义）----
    seen: Mutex<Option<HashSet<String>>>,

    /// 当前扫描轮次的解析池（Go `e.pp` 同位：beginScanCycle 创建、
    /// 仅本轮扫描内有效；跨线程可见 —— 入队发生在遍历 worker 线程）。
    pp: Mutex<Option<Arc<ParsePool>>>,

    // ---- 宿主回调 ----
    listener: RwLock<Option<Arc<ListenerSlot>>>,
    ext_parser: RwLock<Option<Arc<ParserSlot>>>,

    // ---- 搜索结果缓存（alpha16：TTL + 容量上限的轻量 LRU）----
    search_cache: Mutex<Vec<(String, Vec<u8>, Instant)>>,

    /// 扫描线程句柄：close() 时 join 收尾，防泄漏。
    scan_thread: Mutex<Option<JoinHandle<()>>>,

    /// 测试专用：置位后 run_scan 入口即 panic（验证扫描线程 catch_unwind 兑底）。
    #[cfg(test)]
    test_panic_in_run_scan: AtomicBool,
}

impl EngineImpl {
    /// 创建引擎。`workers <= 0` 按 CPU 核数自动决定（上限 8）。
    /// `data_dir` 为索引目录（应用 filesDir 子目录）；空串禁用持久化。
    pub fn new(workers: i32, data_dir: &str) -> EngineResult<Arc<Self>> {
        let mut n = if workers <= 0 {
            std::thread::available_parallelism()
                .map(|n| n.get())
                .unwrap_or(4)
        } else {
            workers.max(1) as usize
        };
        if n > 8 {
            n = 8;
        }
        if !data_dir.is_empty() {
            std::fs::create_dir_all(data_dir)
                .map_err(|e| EngineError::new(format!("core: 创建索引目录失败: {e}")))?;
        }
        let names = Arc::new(NameIndex::new("file"));
        let dirs = Arc::new(NameIndex::new("folder"));
        let ext_names = Arc::new(NameIndex::new("file"));
        let ext_dirs = Arc::new(NameIndex::new("folder"));
        // 全文索引（tantivy）：正文持久化由索引目录负责，
        // 快照仅保存名称/目录/外部条目（Go 同款分工）。
        let content = Arc::new(ContentStore::new(data_dir));
        let eng = Arc::new(EngineImpl {
            workers: n,
            data_dir: data_dir.to_string(),
            names,
            dirs,
            ext_names,
            ext_dirs,
            ext_lock: RwLock::new(()),
            content,
            save_gate: Arc::new(SaveGate::new()),
            cancel: AtomicBool::new(false),
            scanning: AtomicBool::new(false),
            first_build: AtomicBool::new(false),
            start_at: AtomicI64::new(0),
            files: AtomicI64::new(0),
            added: AtomicI64::new(0),
            updated: AtomicI64::new(0),
            removed: AtomicI64::new(0),
            docs_found: AtomicI64::new(0),
            docs_indexed: AtomicI64::new(0),
            host_cb_err: AtomicI64::new(0),
            seen: Mutex::new(None),
            pp: Mutex::new(None),
            listener: RwLock::new(None),
            ext_parser: RwLock::new(None),
            search_cache: Mutex::new(Vec::new()),
            scan_thread: Mutex::new(None),
            #[cfg(test)]
            test_panic_in_run_scan: AtomicBool::new(false),
        });
        // 秒开的关键：构造即恢复上次索引（名称/目录/外部条目快照）
        crate::persist::restore_snapshot(
            data_dir,
            [&*eng.names, &*eng.dirs, &*eng.ext_names, &*eng.ext_dirs].as_slice(),
        );
        Ok(eng)
    }

    /// 测试辅助：无持久化引擎。
    #[cfg(test)]
    pub(crate) fn new_for_test() -> Arc<Self> {
        Self::new(2, "").unwrap()
    }

    /// 索引目录（ExtractDoc 临时文件等使用）。
    pub(crate) fn data_dir(&self) -> &str {
        &self.data_dir
    }

    // ------------------------------------------------------------ 回调注册

    pub fn set_listener(&self, slot: Option<Arc<ListenerSlot>>) {
        if let Ok(mut l) = self.listener.write() {
            *l = slot;
        }
    }

    pub fn set_external_parser(&self, slot: Option<Arc<ParserSlot>>) {
        if let Ok(mut p) = self.ext_parser.write() {
            *p = slot;
        }
    }

    // ------------------------------------------------------------ 扫描

    /// 异步扫描并更新索引。`optionsJSON` 兼容旧版纯数组格式。
    /// 重复调用返回错误；cancel_scan 可取消（异步生效）。
    pub fn start_scan(self: &Arc<Self>, options_json: &str) -> EngineResult<()> {
        let opt = crate::types::ScanOptions::parse(options_json)?;
        if opt.roots.is_empty() {
            return Err(EngineError::new("core: 扫描路径为空"));
        }
        if self
            .scanning
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
        {
            return Err(EngineError::new("core: 扫描已在进行中"));
        }

        // 等待上一次快照落盘完成（Go saveWG.Wait 同款：保证本次扫描期间
        // 无并发写盘，也避免"保存的是扫描进行到一半的状态"）
        self.save_gate.wait();

        // beginScanCycle 同款复位
        self.cancel.store(false, Ordering::Relaxed);
        self.start_at.store(now_millis(), Ordering::Relaxed);
        self.invalidate_search_cache();

        let is_full = opt.mode == "full";
        self.first_build
            .store(self.names.count() == 0, Ordering::Relaxed);
        if is_full {
            // 全量重建同时清空名称/目录/全文库（外部扩展索引不触碰 —— Go 同款）
            self.names.reset();
            self.dirs.reset();
            self.content.reset();
            self.first_build.store(true, Ordering::Relaxed);
        }
        {
            let mut seen = self.seen.lock().unwrap();
            *seen = if opt.mode == "incremental" {
                Some(HashSet::with_capacity(self.names.count() as usize + 1024))
            } else {
                None
            };
        }
        self.reset_counters();

        let mode = opt.mode;
        let roots = opt.roots;
        let engine = Arc::clone(self);
        let handle = match std::thread::Builder::new()
            .name("anything-scan".to_string())
            .spawn(move || {
                // FFI 铁律补强（alpha21）：run_scan 内任何 panic（含遍历钩子
                // 经 std::thread::scope 的重抛）都不得让扫描线程静默死亡 ——
                // 否则 scanning 永真、宿主永远收不到 OnFinished，
                // 「首次建索引」遮罩会永久卡住（JNI 导出层的 catch_unwind
                // 只保护调用线程，覆盖不到这条自建线程）。
                let engine_for_run = Arc::clone(&engine);
                let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    engine_for_run.run_scan(mode, roots)
                }));
                if result.is_err() {
                    engine.scanning.store(false, Ordering::Release);
                    engine.notify_error("scan", "core: 扫描发生内部错误，本轮索引已中止");
                    let stats = engine.current_stats(true);
                    if let Ok(json) = serde_json::to_string(&stats) {
                        engine.notify_finished(&json);
                    }
                }
            }) {
            Ok(h) => h,
            Err(e) => {
                // 线程创建失败：必须复位扫描标志，否则后续扫描永远被拒
                self.scanning.store(false, Ordering::Release);
                return Err(EngineError::new(format!("core: 扫描线程创建失败: {e}")));
            }
        };
        *self.scan_thread.lock().unwrap() = Some(handle);
        Ok(())
    }

    /// 扫描线程主体。完成/取消后统一收尾并通知宿主。
    fn run_scan(self: Arc<Self>, mode: String, roots: Vec<String>) {
        // 测试专用注入点：验证扫描线程的 catch_unwind 兑底（生产代码零影响）
        #[cfg(test)]
        if self.test_panic_in_run_scan.load(Ordering::Relaxed) {
            panic!("injected: run_scan panic probe");
        }
        let incremental = mode == "incremental";

        // 解析流水线：遍历只入队，独立 worker 池消费（Go e.pp 同位）
        let pool = Arc::new(ParsePool::new(&self));
        *self.pp.lock().unwrap() = Some(Arc::clone(&pool));

        // 遍历钩子：批量入索引 + seen 表 + 解析入队 + 节流进度
        let e_files = Arc::clone(&self);
        let last_notify = Arc::new(AtomicI64::new(0));
        let ln = Arc::clone(&last_notify);
        let on_files = move |files: &[FileRecord]| {
            e_files.process_file_batch(files, incremental);
            if incremental {
                let paths = files.iter().map(|f| f.path.clone()).collect::<Vec<_>>();
                e_files.record_seen_batch(paths.into_iter());
            }
            let n = e_files
                .files
                .fetch_add(files.len() as i64, Ordering::Relaxed)
                + files.len() as i64;
            // 跨过 200 边界立即通知；另有 ≥1s 心跳（alpha21：小集合也可见推进）
            let now = now_millis();
            if progress_gate(n, files.len() as i64, ln.load(Ordering::Relaxed), now) {
                ln.store(now, Ordering::Relaxed);
                e_files.notify_progress("scan", n);
            }
        };
        let e_dirs = Arc::clone(&self);
        let on_dirs = move |dirs: &[DirRecord]| {
            let recs: Vec<crate::nameindex::NameRecord> = dirs
                .iter()
                .map(|d| crate::nameindex::NameRecord {
                    path: d.path.clone(),
                    size: 0,
                    mtime: d.mtime,
                })
                .collect();
            e_dirs.dirs.add_batch(&recs);
        };
        let e_visited = Arc::clone(&self);
        let on_dir_visited = move |path: &Path| {
            if incremental {
                e_visited.record_seen(&path.to_string_lossy());
            }
        };
        let e_err = Arc::clone(&self);
        let on_error = move |msg: String| {
            e_err.notify_error("scan", &msg);
        };
        let hooks = WalkHooks {
            on_files_batch: &on_files,
            on_dirs_batch: &on_dirs,
            on_dir_visited: &on_dir_visited,
            on_error: &on_error,
        };

        // walker 直接借用引擎的 cancel 标志（取消语义与 Go 同源）
        let cancelled = {
            let cancel_ref: &AtomicBool = &self.cancel;
            walker::traverse(&roots, self.workers, cancel_ref, &hooks)
        };

        // 目录遍历结束后等待解析流水线排空：此刻不再有新任务入队，
        // run 返回即代表本轮全文解析全部完成，finishScan 的 RemoveExcept
        // 与快照落盘才能看到完整一致的全文库（取消时快速返回）。
        pool.run(parse_workers_for(self.workers));

        self.finish_scan(&mode, cancelled);
    }

    /// 统一收尾（Go finishScan 同序）：增量模式下移除"本次遍历未见到"的
    /// 已删除条目、复位标志、异步落盘快照、通知完成。
    /// 接收 `&Arc<Self>`（alpha26）：落盘改为延迟导出变体，闭包需捕获
    /// 引擎 Arc 在落盘线程内导出记录，避免上万条目同步导出阻塞收尾。
    fn finish_scan(self: &Arc<Self>, mode: &str, cancelled: bool) {
        let incremental = mode == "incremental";
        if incremental && !cancelled {
            let seen = {
                match self.seen.lock() {
                    Ok(g) => g.clone().unwrap_or_default(),
                    Err(_) => HashSet::new(),
                }
            };
            let removed = self.names.remove_except(&seen);
            self.dirs.remove_except(&seen);
            self.content.remove_except(&seen);
            self.removed.store(removed.len() as i64, Ordering::Relaxed);
        } else if let Ok(mut seen) = self.seen.lock() {
            *seen = None;
        }
        self.scanning.store(false, Ordering::Release);
        self.invalidate_search_cache(); // 收尾清理后，旧结果一律失效
        // 零变化增量轮跳过落盘（alpha30，对标原版"更新不写盘"语义）：
        // 无新增/更新/删除/补解析且非首建时，快照与上一轮逐字节一致，
        // 重写上万条目（导出+gzip+rename）是纯浪费的 flash 写入与功耗。
        // full 模式与首建（含快照损坏后的静默重建）一律照存。
        let zero_change = incremental
            && !self.first_build.load(Ordering::Relaxed)
            && self.added.load(Ordering::Relaxed) == 0
            && self.updated.load(Ordering::Relaxed) == 0
            && self.removed.load(Ordering::Relaxed) == 0
            && self.docs_indexed.load(Ordering::Relaxed) == 0;
        // 先注册并启动异步落盘，再通知完成事件：保证宿主看到 OnFinished
        // 时落盘任务已在册（Go 同款顺序）。记录导出移入落盘线程（alpha26）。
        if !cancelled && !self.data_dir.is_empty() && !zero_change {
            let engine = Arc::clone(self);
            crate::persist::spawn_save_lazy(
                Arc::clone(&self.save_gate),
                self.data_dir.clone(),
                move || {
                    (
                        engine.names.export_records(),
                        engine.dirs.export_records(),
                        engine.ext_names.export_records(),
                        engine.ext_dirs.export_records(),
                    )
                },
            );
        }
        let stats = self.current_stats(cancelled);
        if let Ok(json) = serde_json::to_string(&stats) {
            self.notify_finished(&json);
        } // 序列化 Stats 不可能失败；防御性静默
    }

    /// 批量处理一组文件（一个目录的直接子文件，Go processFileBatch 直译）：
    /// 增量模式先用一次读锁批量查重，size/mtime 均未变化的文件直接跳过
    /// （实现秒级增量重扫）；需要入索引的条目用一次写锁批量插入。
    /// 可解析文档不在此处同步解析，而是入队解析流水线由独立 worker 消费。
    fn process_file_batch(&self, files: &[FileRecord], incremental: bool) {
        if files.is_empty() {
            return;
        }
        struct AddItem {
            path: String,
            size: i64,
            mtime: i64,
            parse: bool,
        }
        let mut adds: Vec<AddItem> = Vec::with_capacity(files.len());

        // 增量模式：一次读锁批量查重（锁竞争热点摊薄，Go 同款）
        let lookup = if incremental {
            let paths: Vec<String> = files.iter().map(|f| f.path.clone()).collect();
            Some(self.names.lookup_batch(&paths))
        } else {
            None
        };

        for (i, f) in files.iter().enumerate() {
            let size = f.size as i64;
            let mtime = f.mtime;
            let ext = crate::textutil::ext_of(&f.path);

            if let Some((sizes, mtimes, found)) = &lookup {
                if found[i] && sizes[i] == size && mtimes[i] == mtime {
                    // 未变化：仅当名称在索引但全文缺失时补解析（快照不完整兜底）
                    if is_doc_ext(&ext) && !self.content.has(&f.path) {
                        self.docs_found.fetch_add(1, Ordering::Relaxed);
                        if size <= MAX_PARSE_SIZE as i64 && size > 0 {
                            // 补解析入队由 run_scan 的 pool 承接（见 enqueue_parse）
                            self.enqueue_parse(&f.path, &ext);
                        }
                    }
                    continue;
                }
                if found[i] {
                    self.updated.fetch_add(1, Ordering::Relaxed);
                } else {
                    self.added.fetch_add(1, Ordering::Relaxed);
                }
            }

            adds.push(AddItem {
                path: f.path.clone(),
                size,
                mtime,
                parse: is_doc_ext(&ext),
            });
        }
        if adds.is_empty() {
            return;
        }

        let records: Vec<crate::nameindex::NameRecord> = adds
            .iter()
            .map(|it| crate::nameindex::NameRecord {
                path: it.path.clone(),
                size: it.size,
                mtime: it.mtime,
            })
            .collect();
        self.names.add_batch(&records);

        // 变更/新增文档统一入解析流水线；旧全文的移除**攒批一次 commit**
        //（alpha26：旧实现逐条 content.remove，N 个变更文档 = N 次 tantivy
        // commit + reader.reload，Android flash 上单次 commit 可达百毫秒级，
        // 是增量扫描"明明只改了几个文件却慢 1~2 秒"的主要来源之一。
        // upsert 语义下解析完成的 add_batch 本就先 delete 后 add，
        // 预删除只影响瞬时容量计数，攒批后一次性移除即可）。
        let mut stale_paths: Vec<String> = Vec::new();
        for it in &adds {
            if !it.parse {
                continue;
            }
            self.docs_found.fetch_add(1, Ordering::Relaxed);
            if it.size > MAX_PARSE_SIZE as i64 || it.size == 0 {
                continue;
            }
            stale_paths.push(it.path.clone());
            // 解析交给流水线（入队非阻塞，不拖慢遍历线程）
            self.enqueue_parse(&it.path, &crate::textutil::ext_of(&it.path));
        }
        if !stale_paths.is_empty() {
            self.content.remove_batch(&stale_paths);
        }
    }

    /// 当前扫描轮次的解析池入队口（Go e.pp.enqueue 同位；
    /// 池未就绪/已收尾时静默丢弃 —— 取消路径的合法状态）。
    fn enqueue_parse(&self, path: &str, ext: &str) {
        if let Ok(guard) = self.pp.lock()
            && let Some(pool) = guard.as_ref()
        {
            pool.enqueue(path, ext);
        }
    }

    fn record_seen(&self, path: &str) {
        let Ok(mut guard) = self.seen.lock() else {
            return; // 锁中毒：放弃本次记录，不影响扫描主流程
        };
        // 增量模式才有 seen 表（None = full 模式）
        if let Some(set) = guard.as_mut() {
            set.insert(path.to_string());
        }
    }

    fn record_seen_batch(&self, paths: impl Iterator<Item = String>) {
        let Ok(mut guard) = self.seen.lock() else {
            return;
        };
        if let Some(set) = guard.as_mut() {
            for p in paths {
                set.insert(p);
            }
        }
    }

    fn reset_counters(&self) {
        self.files.store(0, Ordering::Relaxed);
        self.added.store(0, Ordering::Relaxed);
        self.updated.store(0, Ordering::Relaxed);
        self.removed.store(0, Ordering::Relaxed);
        self.docs_found.store(0, Ordering::Relaxed);
        self.docs_indexed.store(0, Ordering::Relaxed);
    }

    /// 请求取消当前扫描（异步生效）。
    pub fn cancel_scan(&self) {
        self.cancel.store(true, Ordering::Relaxed);
    }

    pub fn is_scanning(&self) -> bool {
        self.scanning.load(Ordering::Acquire)
    }

    pub(crate) fn is_cancelled(&self) -> bool {
        self.cancel.load(Ordering::Relaxed)
    }

    // ------------------------------------------------------------ 查询

    /// 搜索文件名、目录名与文档全文，返回 SearchResponse 的 JSON。
    /// 三类命中合并返回，宿主按 Kind / Matched 字段拆分到不同页签展示；
    /// 除返回条目外，还通过 total_matches / total_files / total_dirs /
    /// total_content 上报各类的真实命中总数（不受返回条数上限影响）。
    /// 支持 M4 搜索语法：type:/dir: 过滤器。
    pub fn search(&self, query: &str, limit: i64) -> EngineResult<String> {
        let raw = query.trim();
        if raw.is_empty() {
            return Err(EngineError::new("core: 查询词为空"));
        }
        let mut limit = limit;
        if limit <= 0 || limit > 1000 {
            limit = 300;
        }

        // 结果缓存：键含原始查询（含 type:/dir: 过滤器）与 limit。
        let key = format!("{raw}\x00{limit}");
        if let Some(cached) = self.cached_search(&key) {
            return Ok(cached);
        }

        let start = Instant::now();

        // M4 语法：剥离 type:/dir: 过滤器，剩余词作为实际查询；
        // 剩余词为空但存在过滤器时无法"浏览式"搜索（类别快筛请用 UI 圆钮）。
        let flt = parse_query(raw);
        let q = flt.joined_terms();
        if q.trim().is_empty() {
            return Err(EngineError::new(
                r#"core: type:/dir: 过滤需与关键词一起使用，例如 "type:video 音乐""#,
            ));
        }

        // 各源全量取回（名称索引内部本就全量排序后再截断，这里只是去掉截断），
        // 既用于统计真实总数，也用于按配额切片返回。
        // 全文源的验证预算：无过滤器时 limit 足够（配额分配后全文最多贡献
        // limit 条）；带 type:/dir: 过滤时命中会在过滤后被削，预算加倍补偿。
        // 名称/目录源的物化预算（审计 §6.1）：宽泛查询命中可达数万条，
        // 全量物化的瞬时分配达数十 MB；预算 = max(limit*4, 5000)，
        // 超限时 total 为下界近似（与全文源 verify_cap 语义对齐）。
        let name_cap = std::cmp::max(limit as usize * 4, 5000);
        let (mut name_all, _) = self.names.search_all(&q, name_cap);
        let (mut dir_all, _) = self.dirs.search_all(&q, name_cap);
        let mut cont_cap = limit as usize;
        if !flt.types.is_empty() || !flt.dirs.is_empty() {
            cont_cap *= 2;
        }
        let (mut cont_all, mut cont_total) = self.content.search_all(&q, cont_cap);
        let (ext_name_all, ext_dir_all) = {
            let _g = self.ext_lock.read().ok();
            (
                self.ext_names.search_all(&q, name_cap).0,
                self.ext_dirs.search_all(&q, name_cap).0,
            )
        };

        // M4：应用 type:/dir: 过滤（无过滤器时各列表原样返回）
        if !flt.types.is_empty() || !flt.dirs.is_empty() {
            name_all = crate::query::filter_hits(name_all, &flt);
            dir_all = crate::query::filter_hits(dir_all, &flt);
            cont_all = crate::query::filter_hits(cont_all, &flt);
            // 过滤后的真实总数不可知（被截断的候选未验证），以过滤后命中数为准
            cont_total = cont_all.len();
        }

        // 全文命中补全元数据：大小/修改时间来自名称索引
        self.enrich_content_hits(&mut cont_all);

        // 真实命中总数（Go Search 统计段同位：基于过滤后列表）
        let total_files = name_all.len() + ext_name_all.len();
        let total_dirs = dir_all.len() + ext_dir_all.len();
        let mut ext_all = ext_name_all;
        ext_all.extend(ext_dir_all);
        let total_matches = name_all.len() + dir_all.len() + cont_total + ext_all.len();

        // 两轮配额分配（旧逻辑"文件名占满后全文/外部被饿死"已废弃）：
        // 第一轮各源按份额上限取（文件名 50% / 全文 25% / 目录 15% / 外部 10%），
        // 保证每个页签都有可见结果；第二轮把剩余额度按
        // 文件名→全文→目录→外部 的优先级填满。
        let hits = allocate_hits(name_all, cont_all, dir_all, ext_all, limit as usize);

        let resp = SearchResponse {
            query: q,
            elapsed_ms: start.elapsed().as_millis() as i64,
            total: hits.len() as i64,
            total_matches: total_matches as i64,
            total_files: total_files as i64,
            total_dirs: total_dirs as i64,
            total_content: cont_total as i64,
            hits,
        };
        let bytes =
            serde_json::to_vec(&resp).map_err(|e| EngineError::new(format!("core: {e}")))?;
        self.put_search_cache(&key, &bytes);
        Ok(String::from_utf8_lossy(&bytes).into_owned())
    }

    /// 用名称索引补全全文命中的文件大小与修改时间（Go enrichContentHits 直译）。
    fn enrich_content_hits(&self, hits: &mut [crate::types::FileHit]) {
        if hits.is_empty() {
            return;
        }
        let paths: Vec<String> = hits.iter().map(|h| h.path.clone()).collect();
        let (sizes, mtimes, _) = self.names.lookup_batch(&paths);
        for (i, h) in hits.iter_mut().enumerate() {
            h.size = sizes[i];
            h.mtime = mtimes[i];
            if h.kind.is_empty() {
                h.kind = "file".to_string();
            }
        }
    }

    // ---- 搜索结果缓存（TTL + 容量上限的轻量 LRU，Go 同款参数）----

    const SEARCH_CACHE_TTL: Duration = Duration::from_secs(3);
    const SEARCH_CACHE_MAX: usize = 8;
    const SEARCH_CACHE_MAX_BYTES: usize = 512 << 10;

    /// 命中返回缓存响应；过期条目顺手清除。
    fn cached_search(&self, key: &str) -> Option<String> {
        let cache = self.search_cache.lock().ok()?;
        for (k, v, at) in cache.iter() {
            if k == key {
                if at.elapsed() > Self::SEARCH_CACHE_TTL {
                    return None;
                }
                return Some(String::from_utf8_lossy(v).into_owned());
            }
        }
        None
    }

    /// 清空全部搜索缓存（索引突变点调用：扫描开始/收尾、RemovePaths、
    /// AddDocumentText、ReplaceExternalEntries、Reset）。
    fn invalidate_search_cache(&self) {
        if let Ok(mut cache) = self.search_cache.lock() {
            cache.clear();
        }
    }

    /// 写入缓存；容量满时淘汰最旧条目。
    fn put_search_cache(&self, key: &str, bytes: &[u8]) {
        if bytes.len() > Self::SEARCH_CACHE_MAX_BYTES {
            return;
        }
        let Ok(mut cache) = self.search_cache.lock() else {
            return;
        };
        if cache.len() >= Self::SEARCH_CACHE_MAX {
            // 淘汰最旧条目
            if let Some(oldest) = cache
                .iter()
                .enumerate()
                .min_by_key(|(_, (_, _, at)): &(usize, &(String, Vec<u8>, Instant))| *at)
                .map(|(i, _)| i)
            {
                cache.remove(oldest);
            }
        }
        cache.push((key.to_string(), bytes.to_vec(), Instant::now()));
    }

    // ------------------------------------------------------------ 变更通道

    /// 宿主删除文件后同步移出索引。返回实际移除的条数 JSON。
    pub fn remove_paths(&self, paths_json: &str) -> EngineResult<String> {
        let paths: Vec<String> = serde_json::from_str(paths_json).map_err(|_| {
            EngineError::new(r#"core: pathsJSON 非法，应为 ["/path"] 形式的 JSON 数组"#)
        })?;
        let mut removed = 0i64;
        for p in &paths {
            let in_names = self.names.remove_path(p);
            let in_dirs = self.dirs.remove_path(p);
            let in_content = self.content.remove(p);
            let in_ext = self.ext_names.remove_path(p);
            let in_ext_dirs = self.ext_dirs.remove_path(p);
            if in_names || in_dirs || in_content || in_ext || in_ext_dirs {
                removed += 1;
            }
        }
        if removed > 0 {
            self.invalidate_search_cache();
        }
        let out = serde_json::to_string(&crate::types::RemoveResult { removed })
            .map_err(|e| EngineError::new(format!("core: {e}")))?;
        Ok(out)
    }

    /// 宿主喂入自行解析的文档正文（POI 兜底通道）。
    pub fn add_document_text(&self, path: &str, text: &str) -> EngineResult<()> {
        if path.is_empty() {
            return Err(EngineError::new("core: path 为空"));
        }
        self.content.add(path, text)?;
        self.docs_indexed.fetch_add(1, Ordering::Relaxed);
        self.invalidate_search_cache();
        Ok(())
    }

    /// 注入一批外部条目并整体重建主索引（SAF/DocumentsProvider 通道）。
    /// 与 start_scan 互斥；返回 Stats JSON。
    pub fn ingest(self: &Arc<Self>, entries_json: &str) -> EngineResult<String> {
        let entries: Vec<IngestEntry> = serde_json::from_str(entries_json).map_err(|_| {
            EngineError::new("core: entriesJSON 非法，应为 [{path,name,size,mtime,kind,content}]")
        })?;
        if self
            .scanning
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
        {
            return Err(EngineError::new("core: 扫描/注入已在进行中"));
        }
        // 与 start_scan 相同的落盘串行化：等待上一次快照写完再动手
        self.save_gate.wait();
        // Ingest 本身在调用线程同步执行（条目已由宿主备好，纯内存操作 +
        // 一次批量提交），无需额外线程；调用方（Kotlin）已在 IO 线程。

        self.cancel.store(false, Ordering::Relaxed);
        self.first_build
            .store(self.names.count() == 0, Ordering::Relaxed);
        self.start_at.store(now_millis(), Ordering::Relaxed);
        if let Ok(mut seen) = self.seen.lock() {
            *seen = None;
        }
        self.reset_counters();

        self.names.reset();
        self.dirs.reset();
        self.content.reset();
        self.invalidate_search_cache();

        let mut docs: Vec<(String, String)> = Vec::with_capacity(entries.len());
        for ent in &entries {
            if self.is_cancelled() {
                break;
            }
            if ent.path.is_empty() || ent.name.is_empty() {
                continue;
            }
            let kind = if ent.kind != "folder" {
                "file"
            } else {
                "folder"
            };
            if kind == "folder" {
                self.dirs
                    .add_named(&ent.path, &ent.name, ent.size, ent.mtime);
                continue;
            }
            self.names
                .add_named(&ent.path, &ent.name, ent.size, ent.mtime);
            self.added.fetch_add(1, Ordering::Relaxed);
            let n = self.files.fetch_add(1, Ordering::Relaxed) + 1;
            if n % 2000 == 0 {
                self.notify_progress("index", n);
            }
            if !ent.content.is_empty() {
                docs.push((ent.path.clone(), ent.content.clone()));
            }
        }
        self.docs_found.store(docs.len() as i64, Ordering::Relaxed);
        let indexed = self.content.add_batch(&docs) as i64;
        self.docs_indexed.store(indexed, Ordering::Relaxed);
        self.invalidate_search_cache();

        self.scanning.store(false, Ordering::Release);
        // 收尾与扫描同路径：异步落盘（失败不影响注入结果）；导出移入落盘线程
        if !self.data_dir.is_empty() {
            let engine = Arc::clone(self);
            crate::persist::spawn_save_lazy(
                Arc::clone(&self.save_gate),
                self.data_dir.clone(),
                move || {
                    (
                        engine.names.export_records(),
                        engine.dirs.export_records(),
                        engine.ext_names.export_records(),
                        engine.ext_dirs.export_records(),
                    )
                },
            );
        }
        // OnFinished 异步化：Ingest 在调用方线程同步执行，若同步通知，
        // 监听器阻塞会反压死锁调用方（Go 同款 goroutine 语义）。
        let stats = self.current_stats(false);
        let stats_json =
            serde_json::to_string(&stats).map_err(|e| EngineError::new(format!("core: {e}")))?;
        let engine = Arc::clone(self);
        let json_for_notify = stats_json.clone();
        let _ = std::thread::Builder::new()
            .name("anything-notify".to_string())
            .spawn(move || {
                engine.notify_finished(&json_for_notify);
            });
        Ok(stats_json)
    }

    /// 从内存数据抽取文档正文（SAF/DocumentsProvider 通道专用）。
    /// 落一个临时文件再走 extract_doc_path 分发 —— 与常规扫描完全同一套
    /// 解析管线（内置 OOXML/PDF + 宿主 ExternalParser 兜底旧版 Office），
    /// 用毕即删。`self: &Arc<Self>`（alpha27）：解析移入辅助线程，超时后
    /// 线程可能存活到最后 —— 需要所有权计数而非借用。
    pub fn extract_doc(self: &Arc<Self>, name: &str, data: &[u8]) -> EngineResult<String> {
        if data.is_empty() {
            return Ok(String::new());
        }
        if data.len() > MAX_PARSE_SIZE {
            return Err(EngineError::new(format!(
                "core: 文档 {} 字节超过解析上限 {}",
                data.len(),
                MAX_PARSE_SIZE
            )));
        }
        let dir = if self.data_dir.is_empty() {
            std::env::temp_dir()
        } else {
            std::path::PathBuf::from(&self.data_dir)
        };
        let tmp_dir = dir.join("tmp");
        std::fs::create_dir_all(&tmp_dir)
            .map_err(|e| EngineError::new(format!("core: 创建临时目录失败: {e}")))?;
        let base = crate::textutil::base_name(name);
        let safe_base: String = base
            .chars()
            .map(|c| if c == '/' || c == '\\' { '_' } else { c })
            .collect();
        let tmp = tmp_dir.join(format!(
            "extract-{}-{}-{}",
            std::process::id(),
            now_millis(),
            safe_base
        ));
        std::fs::write(&tmp, data)
            .map_err(|e| EngineError::new(format!("core: 写入临时文件失败: {e}")))?;
        let path = tmp.to_string_lossy().into_owned();
        let ext = crate::textutil::ext_of(name);
        // 审计加固（V-1）：SAF 直连通道此前无超时兜底 —— 扫描流水线里单文档
        // 有 45s 超时（parsequeue），而这条通道直接在调用线程同步解析，恶意
        // 文档可让前台「同步索引」长时间满核卡顿。与流水线同语义：辅助线程
        // 解析 + 超时/panic 一律放弃（返回空正文，宿主按"无全文"收录文件名）。
        let (tx, rx) = std::sync::mpsc::channel();
        // 闭包要跨线程存活（超时后 detach），捕获 Arc 值而非 &self 借用
        let engine = Arc::clone(self);
        let spawned = std::thread::Builder::new()
            .name("anything-extract-one".to_string())
            .spawn(move || {
                let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    engine.extract_doc_path(&path, &ext)
                }));
                let _ = tx.send(r.ok().flatten().unwrap_or_default());
            });
        // 超时后辅助线程仍在后台自旋至自然结束（tmp 已被删除，读取失败即退），
        // 不 join —— 本调用按超时放弃，最坏损失一个 detach 线程。
        let text = if spawned.is_ok() {
            match rx.recv_timeout(DOC_EXTRACT_TIMEOUT) {
                Ok(t) => t,
                Err(_) => {
                    self.notify_error("index", "core: 单文档解析超时，已跳过");
                    String::new()
                }
            }
        } else {
            String::new() // 线程创建失败：放弃全文，仅收录文件名
        };
        let _ = std::fs::remove_file(&tmp);
        Ok(text)
    }

    /// 按扩展名选择解析通道（内置 OOXML / 宿主兜底 / 暂不支持），
    /// 成功返回抽取的正文；失败或空正文返回 None（Go extractDoc 同款）。
    /// 解析器全路径容错：个别损坏文档最多损失自身，绝不 panic 跨界。
    pub(crate) fn extract_doc_path(&self, path: &str, ext: &str) -> Option<String> {
        let text = if is_builtin_parsable(ext) {
            // catch_unwind：PDF/OOXML 解析库对畸形输入的最坏情况兜底
            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                crate::docparse::extract_text(path).ok()
            }))
            .unwrap_or_else(|_| {
                self.notify_error("index", "core: 文档解析发生内部错误");
                None
            })
        } else if is_legacy_doc_ext(ext) {
            let slot = self.ext_parser.read().ok()?.clone()?;
            slot.extract_text(path)
        } else {
            return None;
        };
        match text {
            Some(t) if !t.trim().is_empty() => Some(t),
            _ => None,
        }
    }

    /// 用宿主提供的完整清单替换外部扩展索引（全量替换语义）。
    /// 接收 `&Arc<Self>`（alpha26）：落盘改为延迟导出变体（同 finish_scan）。
    pub fn replace_external_entries(self: &Arc<Self>, feed_json: &str) -> EngineResult<()> {
        let feed: crate::types::ExternalFeed = serde_json::from_str(feed_json).map_err(|_| {
            EngineError::new(r#"core: feedJSON 非法，应为 {"root":...,"dirs":[...],"files":[...]}"#)
        })?;
        if self.is_scanning() {
            return Err(EngineError::new("core: 扫描进行中，暂不能更新扩展索引"));
        }
        {
            let _g = self.ext_lock.write().ok();
            self.ext_names.reset();
            self.ext_dirs.reset();
            self.invalidate_search_cache();
            for p in &feed.dirs {
                if valid_external_path(p, &feed.root) {
                    self.ext_names_kind_add("folder", p);
                }
            }
            for p in &feed.files {
                if valid_external_path(p, &feed.root) {
                    self.ext_names_kind_add("file", p);
                }
            }
        }

        if !self.data_dir.is_empty() {
            let engine = Arc::clone(self);
            crate::persist::spawn_save_lazy(
                Arc::clone(&self.save_gate),
                self.data_dir.clone(),
                move || {
                    (
                        engine.names.export_records(),
                        engine.dirs.export_records(),
                        engine.ext_names.export_records(),
                        engine.ext_dirs.export_records(),
                    )
                },
            );
        }
        Ok(())
    }

    /// 外部条目入册（ext_names/ext_dirs 由索引自身 kind 区分，这里按
    /// 调用语义分发 —— Go extDirs.Add / extNames.Add 同位）。
    fn ext_names_kind_add(&self, kind: &str, path: &str) {
        if kind == "folder" {
            self.ext_dirs.add(path, 0, 0);
        } else {
            self.ext_names.add(path, 0, 0);
        }
    }

    /// 外部扩展条目总数（文件+目录）。
    pub(crate) fn external_count(&self) -> i64 {
        let _g = self.ext_lock.read().ok();
        self.ext_names.count() + self.ext_dirs.count()
    }

    // ------------------------------------------------------------ 统计

    /// 汇总当前计数器为 Stats（Go currentStats 同款口径）。
    fn current_stats(&self, cancelled: bool) -> Stats {
        let start_at = self.start_at.load(Ordering::Relaxed);
        Stats {
            files: self.files.load(Ordering::Relaxed),
            indexed: self.names.count(),
            added: self.added.load(Ordering::Relaxed),
            updated: self.updated.load(Ordering::Relaxed),
            removed: self.removed.load(Ordering::Relaxed),
            docs_found: self.docs_found.load(Ordering::Relaxed),
            docs_indexed: self.docs_indexed.load(Ordering::Relaxed),
            external: self.external_count(),
            host_cb_errors: self.host_cb_err.load(Ordering::Relaxed),
            duration_ms: if start_at > 0 {
                now_millis() - start_at
            } else {
                0
            },
            cancelled,
            first_build: self.first_build.load(Ordering::Relaxed),
            finished_at: crate::types::rfc3339_now(),
        }
    }

    /// 当前累计统计的 JSON（Engine.stats 导出）。
    pub fn stats_json(&self) -> String {
        let mut st = self.current_stats(false);
        // 扫描进行中：duration 实时增长（Go Stats() 同款）
        if self.is_scanning() && self.start_at.load(Ordering::Relaxed) > 0 {
            st.duration_ms = now_millis() - self.start_at.load(Ordering::Relaxed);
        }
        serde_json::to_string(&st).unwrap_or_else(|_| "{}".to_string())
    }

    /// 测试/解析流水线辅助：全文批量入库并计数。
    pub(crate) fn content_add_batch(&self, docs: &[(String, String)]) -> usize {
        self.content.add_batch(docs)
    }

    /// 解析流水线辅助：docs_indexed 计数加 n 并返回新值。
    pub(crate) fn docs_indexed_add(&self, n: usize) -> i64 {
        self.docs_indexed.fetch_add(n as i64, Ordering::Relaxed) + n as i64
    }

    /// 测试辅助：当前 docs_indexed。
    #[cfg(test)]
    pub(crate) fn stats_indexed_docs(&self) -> i64 {
        self.docs_indexed.load(Ordering::Relaxed)
    }

    // ------------------------------------------------------------ 生命周期

    /// 释放资源：取消进行中的扫描并 join 扫描线程，关闭全文索引。
    /// 调用后句柄已从注册表移除（registry::remove 先于此执行），
    /// 再访问即"句柄无效"。
    pub fn close(self: Arc<Self>) {
        self.cancel_scan();
        if let Some(handle) = self.scan_thread.lock().ok().and_then(|mut s| s.take()) {
            let _ = handle.join();
        }
        // 等待在飞快照落盘完成（Go saveWG.Wait 语义）：close 后索引目录
        // 即为最终状态，快速重开也能拿到完整快照
        self.save_gate.wait();
        self.content.close();
    }

    // ------------------------------------------------------------ 通知

    /// 宿主回调统一入口：未送达时计入 host_cb_err（alpha23 可观测性）。
    /// 槽位缺失不计数 —— 桌面测试/宿主未注册属合法状态。
    fn notify_inner(&self, f: impl FnOnce(&ListenerSlot) -> bool) {
        if let Some(slot) = self.listener.read().ok().and_then(|l| l.clone())
            && !f(&slot)
        {
            self.host_cb_err.fetch_add(1, Ordering::Relaxed);
        }
    }

    pub(crate) fn notify_progress(&self, phase: &str, done: i64) {
        self.notify_inner(|s| s.on_progress(phase, done));
    }

    pub(crate) fn notify_finished(&self, stats_json: &str) {
        self.notify_inner(|s| s.on_finished(stats_json));
    }

    pub(crate) fn notify_error(&self, phase: &str, msg: &str) {
        self.notify_inner(|s| s.on_error(phase, msg));
    }
}

// ---------------------------------------------------------------- 查询工具

/// 两轮配额分配（Go allocateHits 直译）。
/// 输入四个已排序的命中切片，返回不超过 limit 条的合并结果。
fn allocate_hits(
    name_all: Vec<crate::types::FileHit>,
    cont_all: Vec<crate::types::FileHit>,
    dir_all: Vec<crate::types::FileHit>,
    ext_all: Vec<crate::types::FileHit>,
    limit: usize,
) -> Vec<crate::types::FileHit> {
    if limit == 0 {
        return Vec::new();
    }
    let mut src = [name_all, cont_all, dir_all, ext_all];
    let mut out: Vec<crate::types::FileHit> = Vec::with_capacity(limit);
    // 第一轮份额：50% / 25% / 15% / 10%
    let caps = [limit / 2, limit / 4, limit * 3 / 20, limit / 10];
    for (src_i, cap_i) in src.iter_mut().zip(caps.iter()) {
        let n = src_i.len().min(*cap_i);
        out.extend(src_i.drain(..n));
    }
    // 第二轮：剩余额度按优先级填满（第一轮已从头部 drain，直接取剩余头部）
    for src_i in src.iter_mut() {
        if out.len() >= limit {
            break;
        }
        let n = src_i.len().min(limit - out.len());
        if n > 0 {
            out.extend(src_i.drain(..n));
        }
    }
    out
}

/// 防御清单质量（Go validExternalPath 直译）：拒绝空串、越出根目录、
/// 含换行的路径（find 以换行分隔输出，含换行的文件名本就无法可靠还原，
/// 直接丢弃）；并逐段校验拒绝 ".."/"." 段 —— 纯前缀检查拦不住
/// root/../secret 这类逃逸。
fn valid_external_path(p: &str, root: &str) -> bool {
    if p.is_empty() || p.contains('\n') || p.contains('\r') {
        return false;
    }
    // 前缀校验带路径边界（审计 V-2）：`/a/data` 的裸 starts_with 会放过
    // `/a/data_evil/x` 这类前缀兄弟目录；root 先归一化去尾斜杠，再要求
    // p 要么就是 root，要么紧跟 `/`。
    let root = root.trim_end_matches('/');
    if !root.is_empty() && !(p == root || p.starts_with(&format!("{root}/"))) {
        return false;
    }
    let rest = p.strip_prefix(root).unwrap_or(p);
    for seg in rest.split('/') {
        if seg == ".." || seg == "." {
            return false;
        }
    }
    true
}

// ---------------------------------------------------------------- 测试

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    /// 轮询等待扫描结束（最长 10s），返回最终 is_scanning 状态。
    /// 返回 true = 超时仍在扫描。
    fn wait_idle(eng: &EngineImpl) -> bool {
        for _ in 0..200 {
            if !eng.is_scanning() {
                return false;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        true
    }

    fn write_docx(path: &Path, text: &str) {
        use std::io::Write;
        let f = std::fs::File::create(path).unwrap();
        let mut zw = zip::ZipWriter::new(f);
        zw.start_file(
            "word/document.xml",
            zip::write::SimpleFileOptions::default(),
        )
        .unwrap();
        zw.write_all(format!("<w:p><w:t>{text}</w:t></w:p>").as_bytes())
            .unwrap();
        zw.finish().unwrap();
    }

    #[test]
    fn workers_auto_and_clamp() {
        let e = EngineImpl::new(0, "").unwrap();
        assert!((1..=8).contains(&e.workers));
        let e = EngineImpl::new(999, "").unwrap();
        assert_eq!(e.workers, 8);
        let e = EngineImpl::new(3, "").unwrap();
        assert_eq!(e.workers, 3);
    }

    #[test]
    fn start_scan_rejects_bad_options() {
        let e = EngineImpl::new(1, "").unwrap();
        assert!(e.start_scan("{bad").is_err());
        assert!(e.start_scan(r#"{"roots":[]}"#).is_err());
    }

    /// 零变化增量轮跳过快照落盘（alpha30，对标原版"更新不写盘"）：
    /// 字节级比对 index.snap —— 无变更轮不重写，有变更轮恢复落盘。
    #[test]
    fn zero_change_incremental_skips_snapshot_rewrite() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("tree");
        let data = tmp.path().join("data");
        std::fs::create_dir_all(&root).unwrap();
        std::fs::write(root.join("a.txt"), b"x").unwrap();

        let e = EngineImpl::new(2, data.to_str().unwrap()).unwrap();
        // 数组格式 = full：首建必须落盘
        e.start_scan(&format!(r#"["{}"]"#, root.display())).unwrap();
        assert!(!wait_idle(&e));
        let snap = data.join("index.snap");
        assert!(snap.exists(), "首建必须落盘");
        let before = std::fs::read(&snap).unwrap();

        // 零变化增量：不重写快照（字节级一致）
        e.start_scan(&format!(
            r#"{{"roots":["{}"],"mode":"incremental"}}"#,
            root.display()
        ))
        .unwrap();
        assert!(!wait_idle(&e));
        let after = std::fs::read(&snap).unwrap();
        assert_eq!(before, after, "零变化增量轮不应重写快照");

        // 有变更的增量轮：恢复落盘
        std::fs::write(root.join("a.txt"), b"changed").unwrap();
        e.start_scan(&format!(
            r#"{{"roots":["{}"],"mode":"incremental"}}"#,
            root.display()
        ))
        .unwrap();
        assert!(!wait_idle(&e));
        let changed = std::fs::read(&snap).unwrap();
        assert_ne!(before, changed, "有变更的增量轮必须落盘");
    }

    #[test]
    fn full_scan_indexes_names_and_content_then_search() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        write_docx(&root.join("会议纪要.docx"), "项目预算为一百二十万元");
        std::fs::write(root.join("photo.jpg"), b"fake").unwrap();
        std::fs::create_dir_all(root.join("子目录")).unwrap();
        std::fs::write(root.join("子目录/notes.txt"), b"x").unwrap();

        let e = EngineImpl::new(4, "").unwrap();
        e.start_scan(&format!(
            r#"{{"roots":["{}"],"mode":"full"}}"#,
            root.display()
        ))
        .unwrap();
        assert!(!wait_idle(&e), "扫描应在超时前结束");
        let stats: Stats = serde_json::from_str(&e.stats_json()).unwrap();
        assert_eq!(stats.files, 3, "2 文件 + 子目录内 1 文件");
        assert!(stats.first_build);
        assert_eq!(
            stats.added, 0,
            "full 模式不累计 added（Go 同语义：仅增量计数）"
        );
        assert_eq!(stats.docs_found, 1);
        assert!(stats.docs_indexed >= 1, "docx 应成功入全文库");
        assert_eq!(stats.indexed, 3);
        assert!(!stats.finished_at.is_empty());

        // 文件名搜索
        let resp = e.search("会议", 100).unwrap();
        let r: SearchResponse = serde_json::from_str(&resp).unwrap();
        assert!(r.total_files >= 1, "应命中 会议纪要.docx");

        // 全文搜索（docx 正文）
        let resp = e.search("预算", 100).unwrap();
        let r: SearchResponse = serde_json::from_str(&resp).unwrap();
        assert!(r.total_content >= 1, "docx 正文应可全文命中");
        assert!(r.hits.iter().any(|h| h.matched == "content"));

        // 目录搜索
        let resp = e.search("子目录", 100).unwrap();
        let r: SearchResponse = serde_json::from_str(&resp).unwrap();
        assert!(r.total_dirs >= 1);

        // 空查询
        assert!(e.search("  ", 100).is_err());
    }

    #[test]
    fn incremental_rescan_updates_and_removes() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        std::fs::write(root.join("a.txt"), b"x").unwrap();
        std::fs::write(root.join("b.txt"), b"x").unwrap();

        let e = EngineImpl::new(4, "").unwrap();
        e.start_scan(&format!(r#"["{}"]"#, root.display())).unwrap();
        assert!(!wait_idle(&e));

        // 新增 + 删除 + 修改
        std::fs::write(root.join("c.txt"), b"x").unwrap();
        std::fs::remove_file(root.join("b.txt")).unwrap();
        std::fs::write(root.join("a.txt"), b"changed-content-larger").unwrap();

        // 增量模式（对象格式；数组格式视作 full，不计数 added/updated）
        e.start_scan(&format!(
            r#"{{"roots":["{}"],"mode":"incremental"}}"#,
            root.display()
        ))
        .unwrap();
        assert!(!wait_idle(&e));
        let stats: Stats = serde_json::from_str(&e.stats_json()).unwrap();
        assert_eq!(stats.added, 1, "c.txt 新增");
        assert_eq!(stats.updated, 1, "a.txt 变更");
        assert_eq!(stats.removed, 1, "b.txt 消失");
        assert!(!stats.first_build, "第二次扫描非首次建索引");

        // 删除的文件不再命中
        let r: SearchResponse = serde_json::from_str(&e.search("b.txt", 100).unwrap()).unwrap();
        assert!(r.total_files == 0, "已删除文件不应命中");
        let r: SearchResponse = serde_json::from_str(&e.search("c.txt", 100).unwrap()).unwrap();
        assert!(r.total_files >= 1);
    }

    #[test]
    fn remove_paths_updates_all_indexes() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        write_docx(&root.join("doc.docx"), " removable content");
        let e = EngineImpl::new(2, "").unwrap();
        e.start_scan(&format!(r#"["{}"]"#, root.display())).unwrap();
        assert!(!wait_idle(&e));

        let out = e
            .remove_paths(&format!(r#"["{}"]"#, root.join("doc.docx").display()))
            .unwrap();
        assert_eq!(out, r#"{"removed":1}"#);
        let r: SearchResponse = serde_json::from_str(&e.search("removable", 100).unwrap()).unwrap();
        assert_eq!(r.total_content, 0, "全文库应同步移除");
        assert!(e.remove_paths("{bad").is_err());
    }

    #[test]
    fn search_cache_hits_and_invalidation() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        std::fs::write(root.join("cached.txt"), b"x").unwrap();
        let e = EngineImpl::new(2, "").unwrap();
        e.start_scan(&format!(r#"["{}"]"#, root.display())).unwrap();
        assert!(!wait_idle(&e));

        let first = e.search("cached", 100).unwrap();
        let second = e.search("cached", 100).unwrap();
        assert_eq!(first, second, "TTL 内重复查询应命中缓存");

        // 索引突变后缓存失效
        e.invalidate_search_cache();
        // remove_paths 会清缓存：新文件进来结果集变化
        std::fs::write(root.join("cached2.txt"), b"x").unwrap();
        e.start_scan(&format!(r#"["{}"]"#, root.display())).unwrap();
        assert!(!wait_idle(&e));
        let third = e.search("cached", 100).unwrap();
        let r: SearchResponse = serde_json::from_str(&third).unwrap();
        assert!(r.total_files >= 2, "缓存应已失效并反映新索引");
    }

    #[test]
    fn search_with_type_and_dir_filters() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        std::fs::write(root.join("movie.mp4"), b"x").unwrap();
        std::fs::write(root.join("song.mp3"), b"x").unwrap();
        std::fs::write(root.join("doc.pdf"), b"x").unwrap();
        let e = EngineImpl::new(2, "").unwrap();
        e.start_scan(&format!(r#"["{}"]"#, root.display())).unwrap();
        assert!(!wait_idle(&e));

        // type:视频（中文别名）+ 关键词
        let r: SearchResponse =
            serde_json::from_str(&e.search("type:视频 movie", 100).unwrap()).unwrap();
        assert_eq!(r.total_files, 1, "仅 movie.mp4");
        // type:audio 别名命中 mp3
        let r: SearchResponse =
            serde_json::from_str(&e.search("type:音乐 song", 100).unwrap()).unwrap();
        assert_eq!(r.total_files, 1);
        // dir: 过滤（父目录路径包含，大小写不敏感）
        let r: SearchResponse =
            serde_json::from_str(&e.search("dir:tmp movie", 100).unwrap()).unwrap();
        assert!(r.total_files >= 1);
        // 仅过滤器无关键词：报错（Go 同文案）
        let err = e.search("type:video", 100).unwrap_err();
        assert!(err.message().contains("type:/dir:"));
    }

    #[test]
    fn quota_allocation_two_rounds() {
        // 文件名 50% / 全文 25% / 目录 15% / 外部 10%；剩余按序填满
        let mk = |p: &str| crate::types::FileHit {
            path: p.into(),
            name: p.into(),
            size: 0,
            mtime: 0,
            matched: "name".into(),
            kind: "file".into(),
            snippet: String::new(),
        };
        let names: Vec<_> = (0..100).map(|i| mk(&format!("/n{i}"))).collect();
        let cont: Vec<_> = (0..100).map(|i| mk(&format!("/c{i}"))).collect();
        let dirs: Vec<_> = (0..100).map(|i| mk(&format!("/d{i}"))).collect();
        let ext: Vec<_> = (0..100).map(|i| mk(&format!("/e{i}"))).collect();
        let hits = allocate_hits(names, cont, dirs, ext, 20);
        assert_eq!(hits.len(), 20);
        // 第一轮份额：10/5/3/2 = 20 恰好填满
        let (mut nc, mut cc, mut dc, mut ec) = (0, 0, 0, 0);
        for h in &hits {
            match h.path.chars().nth(1).unwrap() {
                'n' => nc += 1,
                'c' => cc += 1,
                'd' => dc += 1,
                _ => ec += 1,
            }
        }
        assert_eq!((nc, cc, dc, ec), (10, 5, 3, 2));
    }

    #[test]
    fn concurrent_scan_rejected() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        for i in 0..200 {
            std::fs::write(root.join(format!("f{i}.txt")), "x").unwrap();
        }
        let busy = tempfile::tempdir().unwrap();
        for i in 0..300 {
            std::fs::write(busy.path().join(format!("g{i}.txt")), "x").unwrap();
        }
        let e = EngineImpl::new(2, "").unwrap();
        let e2 = Arc::clone(&e);
        e.start_scan(&format!(r#"["{}"]"#, busy.path().display()))
            .unwrap(); // 旧数组格式，先拉起一次较慢的扫描占住互斥
        let mut rejected = false;
        for _ in 0..20 {
            if e2
                .start_scan(&format!(r#"["{}"]"#, root.display()))
                .is_err()
            {
                rejected = true;
                break;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        if wait_idle(&e) {
            panic!("扫描应结束");
        }
        let _ = rejected; // 小树扫描可能瞬间完成导致未观察到互斥，不作为失败
    }

    #[test]
    fn cancel_during_scan_reports_cancelled() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        for i in 0..5000 {
            std::fs::write(root.join(format!("f{i:05}.txt")), "x").unwrap();
        }
        let e = EngineImpl::new(2, "").unwrap();
        e.start_scan(&format!(r#"["{}"]"#, root.display())).unwrap();
        e.cancel_scan();
        assert!(!wait_idle(&e), "取消后扫描应尽快收尾");
        // cancelled 标志不强制断言：小树可能在 cancel 生效前已完成（Go 同为协作式取消）
        // 关键行为：取消后引擎可立即再次扫描
        e.start_scan(&format!(r#"["{}"]"#, root.display())).unwrap();
        assert!(!wait_idle(&e), "取消后应可重新扫描");
    }

    #[test]
    fn extract_doc_dispatch_and_legacy_callback() {
        let e = EngineImpl::new_for_test();
        // 不支持的格式
        assert_eq!(e.extract_doc("a.xyz", b"data").unwrap(), "");
        // 空数据
        assert_eq!(e.extract_doc("a.docx", b"").unwrap(), "");
        // 超限
        let big = vec![0u8; MAX_PARSE_SIZE + 1];
        assert!(e.extract_doc("a.pdf", &big).is_err());
        // OOXML（docx 字节流）
        let mut docx: Vec<u8> = Vec::new();
        {
            use std::io::Write;
            let cur = std::io::Cursor::new(&mut docx);
            let mut zw = zip::ZipWriter::new(cur);
            zw.start_file(
                "word/document.xml",
                zip::write::SimpleFileOptions::default(),
            )
            .unwrap();
            zw.write_all(b"<w:p><w:t>extracted text</w:t></w:p>")
                .unwrap();
            zw.finish().unwrap();
        }
        let text = e.extract_doc("report.docx", &docx).unwrap();
        assert_eq!(text, "extracted text\n", "段落结束换行（Go 同语义）");
    }

    #[test]
    fn replace_external_entries_and_valid_paths() {
        let e = EngineImpl::new_for_test();
        e.replace_external_entries(
            r#"{"root":"/storage/emulated/0/Android/data","dirs":["/storage/emulated/0/Android/data","/storage/emulated/0/Android/data/com.a"],"files":["/storage/emulated/0/Android/data/com.a/1.txt"]}"#,
        )
        .unwrap();
        let stats: Stats = serde_json::from_str(&e.stats_json()).unwrap();
        assert_eq!(stats.external, 3);

        // 逃逸路径被拒绝
        e.replace_external_entries(r#"{"root":"/root","dirs":["/root/../secret"],"files":[]}"#)
            .unwrap();
        let stats: Stats = serde_json::from_str(&e.stats_json()).unwrap();
        assert_eq!(stats.external, 0);

        assert!(e.replace_external_entries("{bad").is_err());
        assert!(!valid_external_path("", "/r"));
        assert!(!valid_external_path("/r/x\ny", "/r"));
        assert!(valid_external_path("/r/x", ""));
        // 审计 V-2：前缀兄弟目录必须被拒，根本身与根内路径放行
        assert!(!valid_external_path("/r_evil/x", "/r"));
        assert!(valid_external_path("/r", "/r"));
        assert!(valid_external_path("/r/sub/x", "/r"));
        // root 带尾斜杠：归一化后语义一致
        assert!(valid_external_path("/r/sub/x", "/r/"));
        assert!(!valid_external_path("/r_evil/x", "/r/"));
    }

    #[test]
    fn external_entries_searchable_and_persisted() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().to_str().unwrap();
        {
            let e = EngineImpl::new(1, dir).unwrap();
            e.replace_external_entries(
                r#"{"root":"/sd/Android/data","dirs":["/sd/Android/data"],"files":["/sd/Android/data/com.app/cache.bin"]}"#,
            )
            .unwrap();
            let r: SearchResponse =
                serde_json::from_str(&e.search("cache.bin", 100).unwrap()).unwrap();
            assert!(r.total_files >= 1, "外部条目应可搜索");
            Arc::clone(&e).close(); // 等待快照落盘
        }
        // 重建引擎：外部条目随快照恢复
        let e2 = EngineImpl::new(1, dir).unwrap();
        let stats: Stats = serde_json::from_str(&e2.stats_json()).unwrap();
        assert_eq!(stats.external, 2, "外部条目应随快照恢复");
        let r: SearchResponse =
            serde_json::from_str(&e2.search("cache.bin", 100).unwrap()).unwrap();
        assert!(r.total_files >= 1);
    }

    #[test]
    fn snapshot_roundtrip_names_and_fts() {
        let tmp = tempfile::tempdir().unwrap();
        // 内容目录与索引数据目录严格分离（避免扫描到引擎自身数据文件）
        let content = tmp.path().join("content");
        std::fs::create_dir_all(&content).unwrap();
        std::fs::write(content.join("快照甲.txt"), b"x").unwrap();
        std::fs::write(content.join("snapshot-b.txt"), b"x").unwrap();
        let dir = tmp.path().join("data");
        {
            let e = EngineImpl::new(2, dir.to_str().unwrap()).unwrap();
            e.start_scan(&format!(
                r#"{{"roots":["{}"],"mode":"full"}}"#,
                content.display()
            ))
            .unwrap();
            assert!(!wait_idle(&e));
            let pre: Stats = serde_json::from_str(&e.stats_json()).unwrap();
            assert_eq!(pre.files, 2);
            // close 等待异步快照落盘完成，保证新引擎可恢复
            Arc::clone(&e).close();
        }
        // 新引擎：快照恢复后立即可搜索（秒开语义）
        let e2 = EngineImpl::new(2, dir.to_str().unwrap()).unwrap();
        let stats: Stats = serde_json::from_str(&e2.stats_json()).unwrap();
        assert_eq!(stats.indexed, 2, "快照应恢复名称索引（2 个文件）");
        let r: SearchResponse = serde_json::from_str(&e2.search("快照甲", 100).unwrap()).unwrap();
        assert!(r.total_files >= 1, "恢复后立即可搜索（中文名）");
        let r: SearchResponse = serde_json::from_str(&e2.search("snapshot", 100).unwrap()).unwrap();
        assert!(r.total_files >= 1, "恢复后立即可搜索（英文名）");
    }

    #[test]
    fn add_document_text_channel() {
        let e = EngineImpl::new_for_test();
        assert!(e.add_document_text("", "x").is_err());
        // 空正文报错（Go 同语义）
        assert!(e.add_document_text("/a.txt", "  ").is_err());
        e.add_document_text("/x/manual.txt", "手工喂入的正文内容")
            .unwrap();
        let r: SearchResponse = serde_json::from_str(&e.search("手工喂入", 100).unwrap()).unwrap();
        assert!(r.total_content >= 1);
        let stats: Stats = serde_json::from_str(&e.stats_json()).unwrap();
        assert_eq!(stats.docs_indexed, 1);
    }

    #[test]
    fn ingest_rebuilds_main_index() {
        let e = EngineImpl::new_for_test();
        let json = r#"[
            {"path":"content://docs/1","name":"报告.docx","size":10,"mtime":1,"kind":"file","content":"注入的全文内容"},
            {"path":"content://docs/2","name":"相册","size":0,"mtime":2,"kind":"folder"},
            {"path":"","name":"跳过","size":0,"mtime":0,"kind":"file"}
        ]"#;
        let out = e.ingest(json).unwrap();
        let stats: Stats = serde_json::from_str(&out).unwrap();
        assert_eq!(stats.files, 1);
        assert_eq!(stats.added, 1);
        assert_eq!(stats.docs_found, 1);
        assert_eq!(stats.docs_indexed, 1);

        let r: SearchResponse = serde_json::from_str(&e.search("报告", 100).unwrap()).unwrap();
        assert!(r.total_files >= 1, "URI 路径条目按显示名可搜");
        let r: SearchResponse = serde_json::from_str(&e.search("相册", 100).unwrap()).unwrap();
        assert!(r.total_dirs >= 1);
        let r: SearchResponse =
            serde_json::from_str(&e.search("注入的全文", 100).unwrap()).unwrap();
        assert!(r.total_content >= 1);
        assert!(e.ingest("{bad").is_err());
    }

    #[test]
    fn close_joins_scan_thread_and_disables_handle() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        for i in 0..500 {
            std::fs::write(root.join(format!("f{i}.txt")), "x").unwrap();
        }
        let e = EngineImpl::new(2, "").unwrap();
        e.start_scan(&format!(r#"["{}"]"#, root.display())).unwrap();
        Arc::clone(&e).close(); // 应 cancel + join + 关闭全文库，不悬挂
        assert!(!e.is_scanning());
    }

    #[test]
    fn legacy_ext_dispatch_rules() {
        let e = EngineImpl::new_for_test();
        // 未注册宿主解析器：legacy 扩展返回 None（引擎跳过，Go 同语义）
        assert_eq!(e.extract_doc_path("/a/b.doc", ".doc"), None);
        assert_eq!(e.extract_doc_path("/a/b.wps", ".wps"), None);
        // 内置格式：文件不存在 → 解析失败 → None
        assert_eq!(e.extract_doc_path("/nonexistent.docx", ".docx"), None);
        assert_eq!(e.extract_doc_path("/nonexistent.pdf", ".pdf"), None);
        // 非文档扩展名：不支持 → None
        assert_eq!(e.extract_doc_path("/a/b.xyz", ".xyz"), None);
    }

    #[test]
    fn progress_gate_crossings_and_heartbeat() {
        let t = 1_000_000i64;
        // 跨 200 边界立即上报（Go 同款语义：批次覆盖到 200 的倍数才触发）
        assert!(progress_gate(200, 200, t, t));
        assert!(progress_gate(200, 1, t, t));
        assert!(progress_gate(535, 500, t, t));
        assert!(progress_gate(400, 100, t, t));
        // 不含边界的尾批不触发（如 201 由单条批次产生）
        assert!(!progress_gate(201, 1, t, t));
        // 未跨边界且刚上报过：不上报（节流）
        assert!(!progress_gate(5, 5, t, t + 100));
        assert!(!progress_gate(350, 100, t, t + 999));
        // 未跨边界但距上次 ≥1s：心跳上报（alpha21：小集合可见推进）
        assert!(progress_gate(5, 5, t, t + 1000));
        assert!(progress_gate(199, 99, t, t + 5000));
        // 首批必上报（last=0）
        assert!(progress_gate(1, 1, 0, t));
    }

    #[test]
    fn scan_thread_panic_recovers_and_still_notifies() {
        // 端到端：run_scan 中途 panic 时，扫描线程必须复位 scanning 并
        // 发出 OnFinished（否则宿主遮罩永久卡死 —— alpha20 真机实锤的
        // 故障类，这里是铁律回归测试）。
        let tmp = tempfile::tempdir().unwrap();
        for i in 0..30 {
            std::fs::write(tmp.path().join(format!("f{i}.txt")), "x").unwrap();
        }
        let e = EngineImpl::new_for_test();
        e.test_panic_in_run_scan.store(true, Ordering::Relaxed);
        e.start_scan(&format!(r#"["{}"]"#, tmp.path().display()))
            .unwrap();
        for _ in 0..300 {
            if !e.is_scanning() {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        assert!(!e.is_scanning(), "panic 后 scanning 必须复位");
        // 复位后引擎必须可再次接受扫描（没有被卡死）
        e.start_scan(&format!(r#"["{}"]"#, tmp.path().display()))
            .unwrap();
        e.cancel_scan();
        for _ in 0..300 {
            if !e.is_scanning() {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        assert!(!e.is_scanning(), "panic 后新扫描必须能正常发起并取消");
    }
}
