//! 索引磁盘持久化 —— 移植 Go `core/persist.go`（格式 v3(gob) → v4(JSON)）。
//!
//! 工作方式：
//! 1. 引擎构造时同步加载快照（若存在），引擎一就绪即可搜索（"秒开"）；
//! 2. 每次扫描成功收尾后异步落盘（编码 + 临时文件原子重命名）；
//! 3. "重建索引"等全量模式结束后同样覆盖旧快照。
//!
//! v4 起正文不进快照：全文库由 tantivy 持久化（见 fts.rs），快照只保存
//! 名称/目录/外部条目，体积 KB 级。旧 Go 版快照（v3，gob+gzip）与
//! 旧 bleve 全文目录对本实现而言均为"无法解析"：加载静默放弃，退回
//! 全量首次建索引；全文目录损坏走 open_or_create 的"删除重建"路径 ——
//! 从 alpha19（Go）升级到 alpha20（Rust）后首次启动自动全量重建。
//!
//! 一致性设计（Go 同款）：
//! - 快照保存与下一次扫描互斥（save_wg 串行化），避免读到半新半旧状态；
//! - 加载失败（文件损坏/版本不符）静默放弃，由全量扫描兜底。

use std::io::{Read, Write};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};

use serde::{Deserialize, Serialize};

use crate::nameindex::{NameIndex, NameRecord};

/// 快照格式版本（v4：Rust 版 JSON 格式首发；Go v3 的 gob 格式无法解析
/// 即静默放弃恢复，与"损坏快照"同一降级路径）。
pub const SNAPSHOT_VERSION: i64 = 4;

/// 名称索引（文件/目录通用）的持久化条目（复用 [`NameRecord`]）。
pub type NameRec = NameRecord;

/// 快照文件结构（JSON）。
#[derive(Debug, Serialize, Deserialize)]
struct Snapshot {
    version: i64,
    saved_at: i64,
    #[serde(default)]
    names: Vec<NameRec>,
    #[serde(default)]
    dirs: Vec<NameRec>,
    #[serde(default)]
    ext_names: Vec<NameRec>,
    #[serde(default)]
    ext_dirs: Vec<NameRec>,
}

/// 快照文件最终路径；临时文件为其加 .tmp 后缀（Go 同款）。
pub fn snapshot_path(data_dir: &str) -> String {
    Path::new(data_dir)
        .join("index.snap")
        .to_string_lossy()
        .into_owned()
}

/// 将四张索引编码落盘（JSON + gzip level 1，Go `gzip.BestSpeed` 同级：
/// 快照以"文本为主"，压缩比 4~6x 而 CPU 开销约 1/3 —— 手机上落盘更快省电）。
/// 写入临时文件后原子重命名，任意时刻崩溃都不会破坏上一次的有效快照。
pub fn save_snapshot(
    data_dir: &str,
    names: &[NameRec],
    dirs: &[NameRec],
    ext_names: &[NameRec],
    ext_dirs: &[NameRec],
) -> crate::error::EngineResult<()> {
    if data_dir.is_empty() {
        return Err(crate::error::EngineError::new(
            "core: 未设置数据目录，无法保存索引",
        ));
    }
    std::fs::create_dir_all(data_dir)
        .map_err(|e| crate::error::EngineError::new(format!("core: 创建索引目录失败: {e}")))?;

    let snap = Snapshot {
        version: SNAPSHOT_VERSION,
        saved_at: crate::types::now_millis(),
        names: names.to_vec(),
        dirs: dirs.to_vec(),
        ext_names: ext_names.to_vec(),
        ext_dirs: ext_dirs.to_vec(),
    };
    let json = serde_json::to_vec(&snap)
        .map_err(|e| crate::error::EngineError::new(format!("core: 快照编码失败: {e}")))?;

    let compressed: Vec<u8> = {
        let mut enc = flate2::write::GzEncoder::new(
            Vec::with_capacity(json.len() / 2),
            flate2::Compression::fast(),
        );
        enc.write_all(&json)
            .map_err(|e| crate::error::EngineError::new(format!("core: 快照压缩失败: {e}")))?;
        enc.finish()
            .map_err(|e| crate::error::EngineError::new(format!("core: 快照压缩失败: {e}")))?
    };

    let final_path = snapshot_path(data_dir);
    let tmp_path = format!("{final_path}.tmp");
    std::fs::write(&tmp_path, compressed)
        .map_err(|e| crate::error::EngineError::new(format!("core: 快照写入失败: {e}")))?;
    std::fs::rename(&tmp_path, &final_path)
        .map_err(|e| crate::error::EngineError::new(format!("core: 快照重命名失败: {e}")))?;
    Ok(())
}

/// 从磁盘恢复索引（进程内只执行一次，引擎构造时调用）。
/// 首次使用（无快照）与加载失败均静默返回，由后续全量扫描兜底。
pub fn restore_snapshot(data_dir: &str, targets: &[&NameIndex]) {
    if data_dir.is_empty() {
        return;
    }
    let Ok(data) = std::fs::read(snapshot_path(data_dir)) else {
        return; // 无快照：首次使用
    };
    let mut gz = flate2::read::GzDecoder::new(&data[..]);
    let mut json = Vec::new();
    if gz.read_to_end(&mut json).is_err() {
        return; // 非 gzip 格式（旧 Go 快照）：放弃恢复
    }
    let snap: Snapshot = match serde_json::from_slice(&json) {
        Ok(s) => s,
        Err(_) => return, // 损坏（或旧 gob 格式）：放弃恢复
    };
    if snap.version != SNAPSHOT_VERSION {
        return; // 版本不符：放弃恢复
    }
    if targets.len() < 4 {
        return;
    }
    targets[0].import_records(&snap.names);
    targets[1].import_records(&snap.dirs);
    targets[2].import_records(&snap.ext_names);
    targets[3].import_records(&snap.ext_dirs);
}

// ---------------------------------------------------------------- 落盘串行化

/// 扫描落盘等待门（Go `saveWG` 的 Rust 等价物）：
/// StartScan 入口 wait() 保证"上一次快照落盘"与"本次扫描"互斥，
/// 避免"保存的是扫描进行到一半的状态"。
#[derive(Default)]
pub struct SaveGate {
    busy: AtomicBool,
    state: Mutex<usize>, // 在飞落盘任务数
    idle: Condvar,
}

impl SaveGate {
    pub fn new() -> Self {
        Self::default()
    }

    /// 注册一次异步落盘（Go saveWG.Add(1) 同款）。
    pub fn begin(&self) {
        if let Ok(mut n) = self.state.lock() {
            *n += 1;
        }
        self.busy.store(true, Ordering::Release);
    }

    /// 落盘任务收尾（Go saveWG.Done() 同款）。
    pub fn end(&self) {
        if let Ok(mut n) = self.state.lock() {
            *n = n.saturating_sub(1);
            if *n == 0 {
                self.idle.notify_all();
            }
        }
    }

    /// 阻塞至全部在飞落盘完成（Go saveWG.Wait() 同款）。
    pub fn wait(&self) {
        let mut n = match self.state.lock() {
            Ok(n) => n,
            Err(_) => return,
        };
        while *n > 0 {
            n = match self.idle.wait(n) {
                Ok(n) => n,
                Err(_) => return,
            };
        }
    }
}

/// 异步落盘入口：注册门 → 后台线程执行 → 收尾。任何失败不拖垮进程
/// （Go 的 recover 语义由线程边界天然保证：panic 只杀死该线程的闭包，
/// Rust 线程 panic 不影响进程，但为防锁中毒仍全路径 catch_unwind）。
pub fn spawn_save(
    gate: Arc<SaveGate>,
    data_dir: String,
    names: Vec<NameRec>,
    dirs: Vec<NameRec>,
    ext_names: Vec<NameRec>,
    ext_dirs: Vec<NameRec>,
) {
    spawn_save_lazy(gate, data_dir, move || (names, dirs, ext_names, ext_dirs));
}

/// 异步落盘（延迟导出变体，alpha26）：把记录导出（上万条目时是
/// 全表遍历 + 字符串克隆，可达上百毫秒）一并移入落盘线程执行，
/// 扫描收尾的关键路径（OnFinished 通知）只负责注册门与启动线程。
/// 闭包内持有引擎 Arc，执行时机由 SaveGate 串行化保证：下一次
/// StartScan 会先 wait 上一次落盘完成，导出看到的是稳定状态。
pub fn spawn_save_lazy<F>(gate: Arc<SaveGate>, data_dir: String, export: F)
where
    F: FnOnce() -> (Vec<NameRec>, Vec<NameRec>, Vec<NameRec>, Vec<NameRec>) + Send + 'static,
{
    gate.begin();
    let gate_in_thread = Arc::clone(&gate);
    let spawned = std::thread::Builder::new()
        .name("anything-snapshot".to_string())
        .spawn(move || {
            let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                let (names, dirs, ext_names, ext_dirs) = export();
                let _ = save_snapshot(&data_dir, &names, &dirs, &ext_names, &ext_dirs);
            }));
            gate_in_thread.end();
        });
    if spawned.is_err() {
        gate.end(); // 线程创建失败：立即释放门
    }
}

// ---------------------------------------------------------------- 测试

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn save_restore_roundtrip() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().to_str().unwrap();

        let names = NameIndex::new("file");
        let dirs = NameIndex::new("folder");
        let ext_names = NameIndex::new("file");
        let ext_dirs = NameIndex::new("folder");
        names.add("/x/报告.docx", 100, 111);
        names.add("/x/photo.jpg", 2048, 222);
        dirs.add("/x/下载目录", 0, 333);
        ext_names.add("/s/Android/data/a.txt", 1, 444);

        save_snapshot(
            dir,
            &names.export_records(),
            &dirs.export_records(),
            &ext_names.export_records(),
            &ext_dirs.export_records(),
        )
        .unwrap();

        // 全新索引恢复
        let n2 = NameIndex::new("file");
        let d2 = NameIndex::new("folder");
        let en2 = NameIndex::new("file");
        let ed2 = NameIndex::new("folder");
        restore_snapshot(dir, &[&n2, &d2, &en2, &ed2]);
        assert_eq!(n2.count(), 2);
        assert_eq!(d2.count(), 1);
        assert_eq!(en2.count(), 1);
        assert_eq!(ed2.count(), 0);
        assert_eq!(n2.search_all("报告", 0).1, 1);
        assert_eq!(d2.search_all("下载", 0).1, 1);
        let (sizes, mtimes, found) = n2.lookup_batch(&["/x/photo.jpg".into()]);
        assert_eq!((sizes[0], mtimes[0], found[0]), (2048, 222, true));
    }

    #[test]
    fn missing_or_corrupt_snapshot_is_silent() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().to_str().unwrap();
        // 无快照：静默
        let n = NameIndex::new("file");
        restore_snapshot(dir, &[&n, &n, &n, &n]);
        assert_eq!(n.count(), 0);

        // 损坏快照：静默放弃
        std::fs::write(snapshot_path(dir), b"garbage not gzip").unwrap();
        restore_snapshot(dir, &[&n, &n, &n, &n]);
        assert_eq!(n.count(), 0);

        // 非 JSON 的 gzip 内容（旧 Go gob 快照模拟）：静默放弃
        let mut enc = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
        enc.write_all(b"\x1f gob binary junk").unwrap();
        std::fs::write(snapshot_path(dir), enc.finish().unwrap()).unwrap();
        restore_snapshot(dir, &[&n, &n, &n, &n]);
        assert_eq!(n.count(), 0);
    }

    #[test]
    fn version_mismatch_discards() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().to_str().unwrap();
        let snap = Snapshot {
            version: 99,
            saved_at: 1,
            names: vec![NameRecord {
                path: "/a".into(),
                size: 1,
                mtime: 1,
            }],
            dirs: vec![],
            ext_names: vec![],
            ext_dirs: vec![],
        };
        let json = serde_json::to_vec(&snap).unwrap();
        let mut enc = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
        enc.write_all(&json).unwrap();
        std::fs::write(snapshot_path(dir), enc.finish().unwrap()).unwrap();
        let n = NameIndex::new("file");
        restore_snapshot(dir, &[&n, &n, &n, &n]);
        assert_eq!(n.count(), 0, "版本不符必须放弃恢复");
    }

    #[test]
    fn atomic_write_leaves_no_tmp() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().to_str().unwrap();
        save_snapshot(dir, &[], &[], &[], &[]).unwrap();
        assert!(Path::new(&snapshot_path(dir)).exists());
        assert!(!Path::new(&format!("{}.tmp", snapshot_path(dir))).exists());
    }

    #[test]
    fn save_gate_serialization() {
        let gate = Arc::new(SaveGate::new());
        gate.begin();
        gate.begin();
        let g2 = Arc::clone(&gate);
        let t = std::thread::spawn(move || {
            std::thread::sleep(std::time::Duration::from_millis(50));
            g2.end();
            g2.end();
        });
        // wait 必须等到两个 end 全部完成才返回
        gate.wait();
        t.join().unwrap();
        gate.wait(); // 幂等
    }
}
