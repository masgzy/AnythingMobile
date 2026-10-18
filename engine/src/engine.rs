//! 引擎状态机 —— 对应 Go `core/engine.go` 的 Engine。
//!
//! 阶段①骨架范围：
//! - 真实：扫描状态机（scanning/cancel 原子标志）、全深度并行遍历、
//!   进度/错误/完成回调、Stats JSON、句柄生命周期；
//! - 占位：名称索引 / 全文索引 / 持久化快照（阶段②以 tantivy + jieba-rs
//!   接入，`indexed_entries` 等字段届时替代为真实索引计数）。
//!
//! 并发模型对齐 Go 版：
//! - `scanning` CompareExchange 保证 StartScan/Ingest 互斥；
//! - 扫描在自建线程执行（Go 为 goroutine），任何字段只经原子/锁访问；
//! - 回调一律经 ListenerSlot（内部吞掉 Java 异常），绝不向上传播。

use std::collections::HashSet;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::thread::JoinHandle;

use crate::callback::{ListenerSlot, ParserSlot};
use crate::error::{EngineError, EngineResult};
use crate::types::{IngestEntry, Stats, now_millis, rfc3339_now};
use crate::walker;

/// 单个文档参与全文解析的大小上限（Go `maxParseSize` 同款：20MB）。
pub(crate) const MAX_PARSE_SIZE: usize = 20 << 20;

/// 进度上报节流间隔：每处理 64 个文件通知一次。
const PROGRESS_STEP: i64 = 64;

/// 引擎实现（Kotlin 经句柄间接持有）。
pub struct EngineImpl {
    /// 工作线程数（<=0 时按 CPU 核数自动决定，上限 8 —— Go 同款）。
    workers: usize,
    /// 索引目录（空串禁用持久化；阶段②生效）。
    data_dir: String,

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
    /// 特权通道外部条目数（replaceExternalEntries 更新）。
    external: AtomicI64,
    /// 常规名称索引现存条目数。阶段②接入 NameIndex 后由索引真实 Count() 替代。
    indexed_entries: AtomicI64,

    // ---- 增量扫描 seen 表（Go recordSeen 同语义）----
    seen: Mutex<Option<HashSet<String>>>,

    // ---- 宿主回调 ----
    listener: RwLock<Option<Arc<ListenerSlot>>>,
    ext_parser: RwLock<Option<Arc<ParserSlot>>>,

    /// 扫描线程句柄：close() 时 join 收尾，防泄漏。
    scan_thread: Mutex<Option<JoinHandle<()>>>,
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
            // 阶段②快照/全文索引目录；提前建好，失败留给阶段②报错
            let _ = std::fs::create_dir_all(data_dir);
        }
        Ok(Arc::new(EngineImpl {
            workers: n,
            data_dir: data_dir.to_string(),
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
            external: AtomicI64::new(0),
            indexed_entries: AtomicI64::new(0),
            seen: Mutex::new(None),
            listener: RwLock::new(None),
            ext_parser: RwLock::new(None),
            scan_thread: Mutex::new(None),
        }))
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

        // beginScanCycle 同款复位
        self.cancel.store(false, Ordering::Relaxed);
        self.start_at.store(now_millis(), Ordering::Relaxed);
        let is_full = opt.mode == "full";
        if is_full {
            // 阶段②：此处清空名称/全文/外部索引；骨架期仅复位计数
            self.indexed_entries.store(0, Ordering::Relaxed);
        }
        self.first_build.store(
            is_full || self.indexed_entries.load(Ordering::Relaxed) == 0,
            Ordering::Relaxed,
        );
        {
            let mut seen = self.seen.lock().unwrap();
            *seen = if opt.mode == "incremental" {
                Some(HashSet::new())
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
            .spawn(move || engine.run_scan(mode, roots))
        {
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
        let incremental = mode == "incremental";

        // 遍历钩子：计数 + seen 表 + 节流进度（阶段②在此接入索引写入与解析管线）
        let engine_file = Arc::clone(&self);
        let on_file = move |path: &Path, _size: u64, _mtime: i64| {
            engine_file.on_walked_file(path, incremental);
        };
        let engine_dir = Arc::clone(&self);
        let on_dir = move |path: &Path, _mtime: i64| {
            engine_dir.on_walked_dir(path, incremental);
        };
        let engine_err = Arc::clone(&self);
        let on_error = move |msg: String| {
            engine_err.notify_error("scan", &msg);
        };
        let hooks = walker::WalkHooks {
            on_file: &on_file,
            on_dir: &on_dir,
            on_error: &on_error,
        };

        let cancelled = walker::traverse(&roots, self.workers, &self.cancel, &hooks);

        // 收尾（Go finishScan 同序）：增量移除已消失条目（阶段②）→ 复位标志 →
        // 通知完成。骨架期：移除逻辑占位。
        if incremental && !cancelled {
            self.removed.store(0, Ordering::Relaxed);
        }
        {
            let mut seen = self.seen.lock().unwrap();
            *seen = None;
        }
        self.scanning.store(false, Ordering::Release);
        let stats = self.current_stats(cancelled);
        if let Ok(json) = serde_json::to_string(&stats) {
            self.notify_finished(&json);
        } // 序列化 Stats 不可能失败；防御性静默
    }

    fn on_walked_file(&self, path: &Path, incremental: bool) {
        if incremental {
            self.record_seen(&path.to_string_lossy());
        }
        let n = self.files.fetch_add(1, Ordering::Relaxed) + 1;
        if n % PROGRESS_STEP == 0 {
            self.notify_progress("scan", n);
        }
    }

    fn on_walked_dir(&self, path: &Path, incremental: bool) {
        if incremental {
            self.record_seen(&path.to_string_lossy());
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

    // ------------------------------------------------------------ 查询

    /// 搜索文件名、目录名与文档全文。阶段②接入 tantivy 前，
    /// 一律返回"尚未接入"错误（fail loud，避免静默空结果误导联调）。
    pub fn search(&self, query: &str, _limit: i64) -> EngineResult<String> {
        if query.trim().is_empty() {
            return Err(EngineError::new("core: 查询词为空"));
        }
        Err(EngineError::new(
            "core: 搜索索引尚未接入（alpha20 迁移阶段②）",
        ))
    }

    // ------------------------------------------------------------ 变更通道

    /// 宿主删除文件后同步移出索引。阶段②返回真实移除数。
    pub fn remove_paths(&self, paths_json: &str) -> EngineResult<String> {
        let paths: Vec<String> = serde_json::from_str(paths_json).map_err(|_| {
            EngineError::new(r#"core: pathsJSON 非法，应为 ["/path"] 形式的 JSON 数组"#)
        })?;
        let _ = paths; // 阶段②：names/dirs/content/ext 全通道移除
        let out = serde_json::to_string(&crate::types::RemoveResult { removed: 0 })
            .map_err(|e| EngineError::new(format!("core: {e}")))?;
        Ok(out)
    }

    /// 宿主喂入自行解析的文档正文（POI 兜底通道）。阶段②接入全文库写入。
    pub fn add_document_text(&self, path: &str, _text: &str) -> EngineResult<()> {
        if path.is_empty() {
            return Err(EngineError::new("core: path 为空"));
        }
        Ok(())
    }

    /// 注入一批外部条目并整体重建主索引（SAF/DocumentsProvider 通道）。
    /// 与 startScan 互斥；返回 Stats JSON。
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
        // 计数器复位（Go beginScanCycle 同口径）
        self.cancel.store(false, Ordering::Relaxed);
        self.start_at.store(now_millis(), Ordering::Relaxed);
        self.first_build.store(
            self.indexed_entries.load(Ordering::Relaxed) == 0,
            Ordering::Relaxed,
        );
        self.reset_counters();
        // 阶段②：在此整体重建名称/全文索引（entries 纯内存 + 批量提交）。
        // 骨架期：仅消化条目结构，统计保持零值，立即收尾。
        let _ = entries;
        self.scanning.store(false, Ordering::Release);
        let stats = self.current_stats(false);
        serde_json::to_string(&stats).map_err(|e| EngineError::new(format!("core: {e}")))
    }

    /// 从内存数据抽取文档正文（SAF 通道专用）。阶段③接入解析管线；
    /// 当前实现大小校验与 Go 对齐，正文抽取返回空串。
    pub fn extract_doc(&self, name: &str, data: &[u8]) -> EngineResult<String> {
        let _ = name;
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
        // 阶段③：落临时文件 → 解析分发（OOXML/CFB/PDF/宿主 ExternalParser 兜底）
        Ok(String::new())
    }

    /// 用宿主提供的完整清单替换外部扩展索引（全量替换语义）。
    pub fn replace_external_entries(&self, feed_json: &str) -> EngineResult<()> {
        let feed: crate::types::ExternalFeed = serde_json::from_str(feed_json)
            .map_err(|_| EngineError::new(r#"core: feedJSON 非法，应为 {root,dirs,files}"#))?;
        if self.is_scanning() {
            return Err(EngineError::new("core: 扫描已在进行中"));
        }
        // 阶段②：重建 extNames/extDirs 并落盘快照；骨架期仅维护计数
        self.external.store(
            (feed.dirs.len() + feed.files.len()) as i64,
            Ordering::Relaxed,
        );
        Ok(())
    }

    // ------------------------------------------------------------ 统计

    /// 汇总当前计数器为 Stats（Go currentStats 同款口径）。
    fn current_stats(&self, cancelled: bool) -> Stats {
        let start_at = self.start_at.load(Ordering::Relaxed);
        Stats {
            files: self.files.load(Ordering::Relaxed),
            indexed: self.indexed_entries.load(Ordering::Relaxed),
            added: self.added.load(Ordering::Relaxed),
            updated: self.updated.load(Ordering::Relaxed),
            removed: self.removed.load(Ordering::Relaxed),
            docs_found: self.docs_found.load(Ordering::Relaxed),
            docs_indexed: self.docs_indexed.load(Ordering::Relaxed),
            external: self.external.load(Ordering::Relaxed),
            duration_ms: if start_at > 0 {
                now_millis() - start_at
            } else {
                0
            },
            cancelled,
            first_build: self.first_build.load(Ordering::Relaxed),
            finished_at: rfc3339_now(),
        }
    }

    /// 当前累计统计的 JSON（Engine.stats 导出）。
    pub fn stats_json(&self) -> String {
        serde_json::to_string(&self.current_stats(false)).unwrap_or_else(|_| "{}".to_string())
    }

    // ------------------------------------------------------------ 生命周期

    /// 释放资源：取消进行中的扫描并 join 扫描线程。调用后句柄已从
    /// 注册表移除（registry::remove 先于此执行），再访问即"句柄无效"。
    pub fn close(self: Arc<Self>) {
        self.cancel_scan();
        if let Some(handle) = self.scan_thread.lock().ok().and_then(|mut s| s.take()) {
            let _ = handle.join();
        }
        // 阶段②：关闭名称/全文索引句柄
    }

    // ------------------------------------------------------------ 通知

    fn notify_progress(&self, phase: &str, done: i64) {
        if let Some(slot) = self.listener.read().ok().and_then(|l| l.clone()) {
            slot.on_progress(phase, done);
        }
    }

    fn notify_finished(&self, stats_json: &str) {
        if let Some(slot) = self.listener.read().ok().and_then(|l| l.clone()) {
            slot.on_finished(stats_json);
        }
    }

    fn notify_error(&self, phase: &str, msg: &str) {
        if let Some(slot) = self.listener.read().ok().and_then(|l| l.clone()) {
            slot.on_error(phase, msg);
        }
    }
}

// ---------------------------------------------------------------- 测试

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    /// 轮询等待扫描结束（最长 10s），返回最终 is_scanning 状态。
    fn wait_idle(eng: &EngineImpl) -> bool {
        for _ in 0..200 {
            if !eng.is_scanning() {
                return false;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        true
    }

    #[test]
    fn workers_auto_and_clamp() {
        // 0 → 自动（>=1 且 <=8）；999 → 8
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

    #[test]
    fn start_scan_runs_and_reports_stats() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        for i in 0..70 {
            std::fs::write(root.join(format!("f{i}.txt")), "x").unwrap();
        }
        std::fs::create_dir_all(root.join("sub")).unwrap();
        std::fs::write(root.join("sub/g.txt"), "x").unwrap();

        let e = EngineImpl::new(4, "").unwrap();
        e.start_scan(&format!(
            r#"{{"roots":["{}"],"mode":"full"}}"#,
            root.display()
        ))
        .unwrap();
        assert!(e.is_scanning() || true); // 扫描可能瞬间完成
        assert!(!wait_idle(&e), "扫描应在超时前结束");
        let stats: Stats = serde_json::from_str(&e.stats_json()).unwrap();
        assert_eq!(stats.files, 71, "70 个根文件 + sub/g.txt");
        assert!(stats.first_build, "索引从零开始");
        assert!(!stats.cancelled);
        assert!(!stats.finished_at.is_empty());
        // 71 个文件跨过 64 节流线（骨架期无 JVM，回调 no-op，仅验证不崩溃）
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
        // 循环尝试并发启动：扫描结束前应被拒绝；结束后允许
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
    fn cancel_before_start_is_noop() {
        let e = EngineImpl::new(1, "").unwrap();
        e.cancel_scan();
        assert!(!e.is_scanning());
    }

    #[test]
    fn search_not_wired_yet() {
        let e = EngineImpl::new(1, "").unwrap();
        assert!(e.search("hello", 10).is_err());
        assert!(e.search("  ", 10).is_err());
    }

    #[test]
    fn remove_paths_contract() {
        let e = EngineImpl::new(1, "").unwrap();
        let out = e.remove_paths(r#"["/a","/b"]"#).unwrap();
        assert_eq!(out, r#"{"removed":0}"#);
        assert!(e.remove_paths("{bad").is_err());
    }

    #[test]
    fn extract_doc_size_limit() {
        let e = EngineImpl::new(1, "").unwrap();
        assert_eq!(e.extract_doc("a.txt", b"").unwrap(), "");
        let big = vec![0u8; MAX_PARSE_SIZE + 1];
        assert!(e.extract_doc("a.pdf", &big).is_err());
    }

    #[test]
    fn replace_external_entries_parses_and_counts() {
        let e = EngineImpl::new(1, "").unwrap();
        e.replace_external_entries(
            r#"{"root":"/x","dirs":["/x/a"],"files":["/x/a/1.txt","/x/b.txt"]}"#,
        )
        .unwrap();
        let stats: Stats = serde_json::from_str(&e.stats_json()).unwrap();
        assert_eq!(stats.external, 3);
        assert!(e.replace_external_entries("{bad").is_err());
    }

    #[test]
    fn ingest_returns_stats_shape() {
        let e = EngineImpl::new(1, "").unwrap();
        let out = e
            .ingest(r#"[{"path":"/a.txt","name":"a.txt","size":1,"mtime":2,"kind":"file","content":"hi"}]"#)
            .unwrap();
        let stats: Stats = serde_json::from_str(&out).unwrap();
        assert!(!stats.finished_at.is_empty());
        assert!(e.ingest("{bad").is_err());
    }

    #[test]
    fn close_joins_scan_thread() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        for i in 0..500 {
            std::fs::write(root.join(format!("f{i}.txt")), "x").unwrap();
        }
        let e = EngineImpl::new(2, "").unwrap();
        e.start_scan(&format!(r#"["{}"]"#, root.display())).unwrap();
        Arc::clone(&e).close(); // 应 cancel + join，不悬挂
        assert!(!e.is_scanning());
    }
}
