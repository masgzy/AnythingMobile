//! 全深度并行目录遍历 —— 移植 Go `core/walker.go` 的 dirWalker 模型。
//!
//! 工作模型（与 Go 完全一致）：共享目录队列 + workers 个工作线程。
//! 线程拾取目录后顺序处理其条目（文件：回调宿主；子目录：压回队列供
//! 其他线程拾取），因此任意深度都保持 workers 路并行。移动存储经 FUSE
//! 访问时每次 lstat 都有可观延迟，全深度并行把延迟摊薄到每一路。
//!
//! 剪枝规则（Go `filterDir` 同款）：
//! - 隐藏目录/文件（`.` 前缀）；
//! - `LOST.DIR` / `lost+found` / `proc` / `sys` / `dev`；
//! - `Android/data`、`Android/obb`（其他应用私有目录，即使持有
//!   MANAGE_EXTERNAL_STORAGE 也不可访问）。
//!
//! 符号链接语义（与 Go 一致）：`DirEntry::file_type()` 不跟随符号链接，
//! 指向目录的符号链接按"文件"回调（lstat 数据），天然免疫符号链接环。
//!
//! 取消语义：`cancel` 为协作式标志，遍历线程在每个目录/条目粒度检查；
//! 触发后 `stopped` 置位，所有线程不再拾取新任务，`traverse` 返回 true。

use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Condvar, Mutex};

/// 文件条目批次记录（入名称索引 + 增量比对 + 解析入队）。
#[derive(Debug, Clone)]
pub struct FileRecord {
    pub path: String,
    pub size: u64,
    pub mtime: i64,
}

/// 目录条目批次记录（入目录名索引）。
#[derive(Debug, Clone)]
pub struct DirRecord {
    pub path: String,
    pub mtime: i64,
}

/// 遍历事件钩子（**批次语义**，与 Go walkDir 逐目录批量处理对齐：
/// 每目录各一次锁获取，替代逐条目 2~3 次 —— 多线程下的锁竞争热点）。
pub struct WalkHooks<'a> {
    /// 目录批次：子目录（父目录 ReadDir 结果负责名字记录，Go 同款）
    /// 与根目录自身。仅入目录名索引；seen 记录另见 [`Self::on_dir_visited`]。
    pub on_dirs_batch: &'a (dyn Fn(&[DirRecord]) + Sync + Send),
    /// 文件批次：一个目录的直接子文件（或根本身是文件）。seen 记录、
    /// 增量比对、解析入队均由引擎在该钩子内完成（Go processFileBatch 同位）。
    pub on_files_batch: &'a (dyn Fn(&[FileRecord]) + Sync + Send),
    /// 目录 read_dir 成功（seen 记录时机 —— Go recordSeen(dir) 同位：
    /// 读取失败目录按“本轮未见到”清除）。
    pub on_dir_visited: &'a (dyn Fn(&Path) + Sync + Send),
    /// 非致命错误（如目录无权限），不中断整体遍历。
    pub on_error: &'a (dyn Fn(String) + Sync + Send),
}

// ---------------------------------------------------------------- 队列

/// 共享目录任务队列：pending = 队列中 + 正在处理的目录总数；
/// pending 归零即遍历完成。
struct DirQueue {
    state: Mutex<QueueState>,
    cv: Condvar,
}

struct QueueState {
    dirs: VecDeque<PathBuf>,
    pending: usize,
    stopped: bool,
}

impl DirQueue {
    fn new() -> Self {
        DirQueue {
            state: Mutex::new(QueueState {
                dirs: VecDeque::new(),
                pending: 0,
                stopped: false,
            }),
            cv: Condvar::new(),
        }
    }

    fn push(&self, dir: PathBuf) {
        let mut s = self.state.lock().unwrap();
        s.dirs.push_back(dir);
        s.pending += 1;
        self.cv.notify_one();
    }

    /// 拾取：队列空且还有在处理目录时等待；队列空且无在处理（或已停止）
    /// 返回 None —— 线程退出条件。
    fn pop(&self) -> Option<PathBuf> {
        let mut s = self.state.lock().unwrap();
        loop {
            if let Some(dir) = s.dirs.pop_front() {
                return Some(dir);
            }
            if s.stopped || s.pending == 0 {
                return None;
            }
            s = self.cv.wait(s).unwrap();
        }
    }

    /// 一个目录处理完毕。pending 归零时唤醒全部等待线程收工。
    fn done_one(&self) {
        let mut s = self.state.lock().unwrap();
        s.pending = s.pending.saturating_sub(1);
        if s.pending == 0 {
            self.cv.notify_all();
        }
    }

    /// 请求全部线程停止拾取（取消/内部错误）。
    fn stop(&self) {
        let mut s = self.state.lock().unwrap();
        s.stopped = true;
        self.cv.notify_all();
    }
}

// ---------------------------------------------------------------- 剪枝

/// 目录剪枝规则（Go `filterDir` 逐条对齐，含测试覆盖）。
pub fn filter_dir(path: &Path, name: &str) -> bool {
    if name.starts_with('.') {
        return true;
    }
    match name {
        "LOST.DIR" | "lost+found" | "proc" | "sys" | "dev" => true,
        "data" | "obb" => path
            .parent()
            .and_then(|p| p.file_name())
            .is_some_and(|n| n == "Android"),
        _ => false,
    }
}

/// mtime → unix 毫秒（取不到时 0；Go 同场景为负值或 0，差异无实际影响）。
fn mtime_millis(md: &std::fs::Metadata) -> i64 {
    md.modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

// ---------------------------------------------------------------- 遍历

/// 并发遍历 roots。返回是否被取消。
///
/// 根语义（Go `traverse` 同款）：根可以是文件（单文件回调后跳过）；
/// 根目录不存在/无权限时静默跳过（os.Stat 错误路径同款）。
pub fn traverse(roots: &[String], workers: usize, cancel: &AtomicBool, hooks: &WalkHooks) -> bool {
    let workers = workers.max(1);
    let queue = DirQueue::new();

    let mut root_dirs: Vec<DirRecord> = Vec::new();
    for root in roots {
        if cancel.load(Ordering::Relaxed) {
            queue.stop();
            return true;
        }
        let path = PathBuf::from(root);
        // 根跟随符号链接（Go 用 os.Stat 而非 lstat）
        match std::fs::metadata(&path) {
            Ok(md) if md.is_dir() => {
                root_dirs.push(DirRecord {
                    path: root.clone(),
                    mtime: mtime_millis(&md),
                });
                queue.push(path);
            }
            Ok(md) => {
                // 根本身是文件：单条批次回调（Go handleFile 同位）
                (hooks.on_files_batch)(&[FileRecord {
                    path: root.clone(),
                    size: md.len(),
                    mtime: mtime_millis(&md),
                }]);
            }
            Err(_) => {} // 根不存在/不可访问：跳过，不报错（Go 同款静默）
        }
    }
    // 根目录自身批量入目录名索引（Go traverse 循环后 AddBatch(rootDirs) 同位）
    if !root_dirs.is_empty() {
        (hooks.on_dirs_batch)(&root_dirs);
    }

    std::thread::scope(|scope| {
        for _ in 0..workers {
            scope.spawn(|| {
                while let Some(dir) = queue.pop() {
                    let cancelled_now = walk_dir(&dir, cancel, hooks, &queue);
                    queue.done_one();
                    if cancelled_now || cancel.load(Ordering::Relaxed) {
                        queue.stop();
                        break;
                    }
                }
            });
        }
    });

    cancel.load(Ordering::Relaxed)
}

/// 处理单个目录：读取条目 → 子目录记录+压队 / 文件回调。
/// 返回 true 表示检测到取消（调用方据此 stop 全体）。
///
/// 目录记录语义（对齐 Go）：子目录的名字记录由**父目录的 ReadDir 结果**
/// 负责（即使子目录稍后 read_dir 失败也已在册）；目录自身的 seen 记录
/// 则在本目录 read_dir 成功后写入（失败目录按"本轮未见到"清除）。
fn walk_dir(dir: &Path, cancel: &AtomicBool, hooks: &WalkHooks, queue: &DirQueue) -> bool {
    if cancel.load(Ordering::Relaxed) {
        return true;
    }
    let entries = match std::fs::read_dir(dir) {
        Ok(rd) => rd,
        Err(e) => {
            // 无权限等情况：跳过该目录，不中断整体扫描（Go 同款）
            (hooks.on_error)(format!("无法读取目录 {}: {}", dir.display(), e));
            return false;
        }
    };
    // read_dir 成功后才记录目录自身（Go recordSeen 同序）
    (hooks.on_dir_visited)(dir);

    let mut sub_dirs: Vec<DirRecord> = Vec::new();
    let mut file_records: Vec<FileRecord> = Vec::new();

    for child in entries {
        if cancel.load(Ordering::Relaxed) {
            return true;
        }
        let Ok(child) = child else {
            continue; // 竞态删除等瞬时错误只跳过该条目
        };
        let name = child.file_name();
        let name_str = name.to_string_lossy();
        let sub = dir.join(&name);

        let Ok(ft) = child.file_type() else { continue };
        if ft.is_dir() {
            if filter_dir(&sub, &name_str) {
                continue;
            }
            // 子目录名字记录在父目录的条目元数据上取一次 lstat，
            // 不再像旧实现那样处理到该目录时再 stat 一次（Go 同款）
            if let Ok(md) = child.metadata() {
                sub_dirs.push(DirRecord {
                    path: sub.to_string_lossy().into_owned(),
                    mtime: mtime_millis(&md),
                });
            }
            queue.push(sub);
            continue;
        }
        // 文件分支（含符号链接）：隐藏文件跳过；lstat 失败跳过
        if name_str.starts_with('.') {
            continue;
        }
        let Ok(md) = child.metadata() else { continue };
        file_records.push(FileRecord {
            path: sub.to_string_lossy().into_owned(),
            size: md.len(),
            mtime: mtime_millis(&md),
        });
    }

    // 批量收录子目录 + 批量入文件名索引：每目录各一次锁获取，
    // 替代逐条目 2~3 次（多线程下的锁竞争热点，Go 同款顺序）
    if !sub_dirs.is_empty() {
        (hooks.on_dirs_batch)(&sub_dirs);
    }
    if !file_records.is_empty() {
        (hooks.on_files_batch)(&file_records);
    }
    false
}

// ---------------------------------------------------------------- 测试

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    fn no_hooks() -> WalkHooks<'static> {
        WalkHooks {
            on_files_batch: &|_| {},
            on_dirs_batch: &|_| {},
            on_dir_visited: &|_| {},
            on_error: &|_| {},
        }
    }

    #[test]
    fn filter_dir_rules_match_go() {
        assert!(filter_dir(Path::new("/a/.git"), ".git"));
        assert!(filter_dir(Path::new("/a/LOST.DIR"), "LOST.DIR"));
        assert!(filter_dir(Path::new("/a/lost+found"), "lost+found"));
        // proc/sys/dev 同样只按目录名剪枝（子路径不级联）
        assert!(!filter_dir(Path::new("/proc/x"), "x"));
        assert!(filter_dir(Path::new("/x/proc"), "proc"));
        // Android/data 与 Android/obb 剪枝
        assert!(filter_dir(
            Path::new("/storage/emulated/0/Android/data"),
            "data"
        ));
        assert!(filter_dir(
            Path::new("/storage/emulated/0/Android/obb"),
            "obb"
        ));
        // 普通位置同名目录不剪
        assert!(!filter_dir(Path::new("/sdcard/Download/data"), "data"));
        assert!(!filter_dir(Path::new("/x/Android/data2"), "data2"));
        assert!(!filter_dir(Path::new("/x/normal"), "normal"));
    }

    #[test]
    fn traverse_counts_files_and_dirs_with_pruning() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        std::fs::create_dir_all(root.join("sub/deep")).unwrap();
        std::fs::create_dir_all(root.join(".hidden")).unwrap();
        std::fs::create_dir_all(root.join("Android/data")).unwrap();
        std::fs::create_dir_all(root.join("LOST.DIR")).unwrap();
        std::fs::write(root.join("a.txt"), "x").unwrap();
        std::fs::write(root.join(".dotfile"), "x").unwrap();
        std::fs::write(root.join("sub/b.txt"), "x").unwrap();
        std::fs::write(root.join("sub/deep/c.txt"), "x").unwrap();
        std::fs::write(root.join(".hidden/h.txt"), "x").unwrap();
        std::fs::write(root.join("Android/data/d.txt"), "x").unwrap();

        let files = AtomicUsize::new(0);
        let dirs = AtomicUsize::new(0);
        let visited = AtomicUsize::new(0);
        let hooks = WalkHooks {
            on_files_batch: &|fs| {
                files.fetch_add(fs.len(), Ordering::Relaxed);
            },
            on_dirs_batch: &|ds| {
                dirs.fetch_add(ds.len(), Ordering::Relaxed);
            },
            on_dir_visited: &|_| {
                visited.fetch_add(1, Ordering::Relaxed);
            },
            on_error: &|e| panic!("不应有错误: {e}"),
        };
        let cancel = AtomicBool::new(false);
        let cancelled = traverse(&[root.to_string_lossy().into_owned()], 4, &cancel, &hooks);
        assert!(!cancelled);
        assert_eq!(
            files.load(Ordering::Relaxed),
            3,
            "a.txt/sub/b.txt/deep/c.txt"
        );
        // 目录计数：root(根预先入册) + sub + deep + Android
        // （.hidden/LOST.DIR 剪枝；Android/data 剪枝但 Android 本身入册 —— Go 同款）
        assert_eq!(dirs.load(Ordering::Relaxed), 4);
        // 成功访问的目录：root/sub/deep/Android（Android/data 未走 read_dir）
        assert_eq!(visited.load(Ordering::Relaxed), 4);
    }

    #[test]
    fn traverse_root_file_and_missing_root() {
        let tmp = tempfile::tempdir().unwrap();
        let f = tmp.path().join("solo.txt");
        std::fs::write(&f, "x").unwrap();
        let files = AtomicUsize::new(0);
        let hooks = WalkHooks {
            on_files_batch: &|fs| {
                files.fetch_add(fs.len(), Ordering::Relaxed);
            },
            on_dirs_batch: &|_| {},
            on_dir_visited: &|_| {},
            on_error: &|e| panic!("不应有错误: {e}"),
        };
        let cancel = AtomicBool::new(false);
        let roots = vec![
            f.to_string_lossy().into_owned(),
            "/nonexistent-path-xyz".to_string(), // 静默跳过
        ];
        assert!(!traverse(&roots, 2, &cancel, &hooks));
        assert_eq!(files.load(Ordering::Relaxed), 1);
    }

    #[test]
    fn traverse_honours_precancel() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::write(tmp.path().join("a.txt"), "x").unwrap();
        let files = AtomicUsize::new(0);
        let hooks = WalkHooks {
            on_files_batch: &|fs| {
                files.fetch_add(fs.len(), Ordering::Relaxed);
            },
            on_dirs_batch: &|_| {},
            on_dir_visited: &|_| {},
            on_error: &|_| {},
        };
        let cancel = AtomicBool::new(true); // 预先取消：应什么都不处理
        let cancelled = traverse(
            &[tmp.path().to_string_lossy().into_owned()],
            2,
            &cancel,
            &hooks,
        );
        assert!(cancelled);
        assert_eq!(files.load(Ordering::Relaxed), 0);
    }

    #[test]
    fn traverse_reports_unreadable_dir_without_dying() {
        let tmp = tempfile::tempdir().unwrap();
        // /proc/self 之类的路径不可控，改用一个不存在的子目录模拟权限错误：
        // read_dir 对不存在路径返回 Err → on_error 回调
        let missing = tmp.path().join("gone");
        let errors = AtomicUsize::new(0);
        let hooks = WalkHooks {
            on_files_batch: &|_| {},
            on_dirs_batch: &|_| {},
            on_dir_visited: &|_| {},
            on_error: &|_| {
                errors.fetch_add(1, Ordering::Relaxed);
            },
        };
        // 直接单测 walk_dir：不存在目录 → 错误回调一次，不 panic
        let q = DirQueue::new();
        let cancel = AtomicBool::new(false);
        let r = walk_dir(&missing, &cancel, &hooks, &q);
        assert!(!r);
        assert_eq!(errors.load(Ordering::Relaxed), 1);
    }
}
