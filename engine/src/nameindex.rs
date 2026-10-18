//! 名称索引 —— 移植 Go `core/nameindex.go`（2-gram 倒排，文件/目录通用）。
//!
//! - 查询词 >= 2 字符：先取各 bigram 倒排表求交集，再对候选做子串验证；
//! - 单字符查询：退化为全量线性扫描（名称基数通常在十万级，可接受）；
//! - `by_path` 提供路径到文档的 O(1) 映射，支撑增量扫描的批量查重 /
//!   RemovePath / RemoveExcept；
//! - 排序：名称更短（**字节长度**，Go `len(name)` 同口径）优先、
//!   修改时间更新优先。
//!
//! 并发模型：单 `RwLock` 包裹全部内部状态；写路径提供批量接口
//! （AddBatch 摊薄锁竞争，Go 同款）。

use std::collections::{HashMap, HashSet};
use std::sync::RwLock;

use crate::types::FileHit;

/// 单条名称记录（持久化条目，Go `nameRecord` 同款）。
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct NameRecord {
    pub path: String,
    #[serde(default)]
    pub size: i64,
    #[serde(default)]
    pub mtime: i64,
}

struct DocInfo {
    path: String,
    name: String,
    lower: String,
    size: i64,
    mtime: i64,
}

#[derive(Default)]
struct Inner {
    kind: &'static str, // "file" | "folder"，写入搜索结果的 Kind 字段
    docs: HashMap<u32, DocInfo>,
    by_path: HashMap<String, u32>,
    grams: HashMap<String, HashSet<u32>>,
    next: u32,
}

impl Inner {
    /// 按给定显示名插入（同路径先删旧记录）。调用方需持有写锁。
    fn insert_named(&mut self, path: &str, name: &str, size: i64, mtime: i64) {
        let lower = crate::textutil::lower_simple(name);
        if lower.is_empty() {
            return;
        }
        if let Some(id) = self.by_path.get(path).copied() {
            self.remove(id);
        }
        let id = self.next;
        self.next += 1;
        for g in crate::textutil::bigrams(&lower) {
            self.grams.entry(g).or_default().insert(id);
        }
        self.docs.insert(
            id,
            DocInfo {
                path: path.to_string(),
                name: name.to_string(),
                lower,
                size,
                mtime,
            },
        );
        self.by_path.insert(path.to_string(), id);
    }

    /// 删除指定 id 的文档及其全部倒排记录。调用方需持有写锁。
    fn remove(&mut self, id: u32) {
        let Some(doc) = self.docs.remove(&id) else {
            return;
        };
        for g in crate::textutil::bigrams(&doc.lower) {
            if let Some(set) = self.grams.get_mut(&g) {
                set.remove(&id);
                if set.is_empty() {
                    self.grams.remove(&g);
                }
            }
        }
        self.by_path.remove(&doc.path);
    }

    /// 计算查询词全部 bigram 的倒排交集。
    /// 返回 None 语义分两种（Go 同款）：
    /// - `Err(())`：某个 bigram 无倒排记录 => 不可能子串命中；
    /// - `Ok(None)`：单字符查询，无需候选过滤；
    /// - `Ok(Some(set))`：候选 id 集。
    fn candidates_for(&self, runes: &[char]) -> Result<Option<HashSet<u32>>, ()> {
        if runes.len() < 2 {
            return Ok(None);
        }
        let mut candidates: Option<HashSet<u32>> = None;
        for i in 0..=runes.len().saturating_sub(2) {
            let gram: String = runes[i..i + 2].iter().collect();
            let set = self.grams.get(&gram).filter(|s| !s.is_empty()).ok_or(())?;
            match &mut candidates {
                None => candidates = Some(set.iter().copied().collect()),
                Some(c) => {
                    let ids: HashSet<u32> = set.iter().copied().collect();
                    c.retain(|id| ids.contains(id));
                    if c.is_empty() {
                        return Err(());
                    }
                }
            }
        }
        Ok(candidates)
    }
}

/// 线程安全的名称索引（Go `NameIndex` 等价物）。
pub struct NameIndex {
    inner: RwLock<Inner>,
}

impl NameIndex {
    /// 创建空的名称索引；kind 取 "file" 或 "folder"。
    pub fn new(kind: &'static str) -> Self {
        NameIndex {
            inner: RwLock::new(Inner {
                kind,
                ..Default::default()
            }),
        }
    }

    /// 将一个名称加入索引；同路径重复加入会先移除旧记录（支持增量重扫）。
    pub fn add(&self, path: &str, size: i64, mtime: i64) {
        let name = crate::textutil::base_name(path);
        self.add_named(path, name, size, mtime);
    }

    /// 显式指定显示名加入索引（SAF/DocumentsProvider 注入通道：
    /// 条目路径可能是 content URI，取不到真实文件名）。
    pub fn add_named(&self, path: &str, name: &str, size: i64, mtime: i64) {
        if let Ok(mut inner) = self.inner.write() {
            inner.insert_named(path, name, size, mtime);
        }
    }

    /// 批量加入名称，语义与多次调用 add 完全一致（同路径先删旧记录），
    /// 但全程只获取一次写锁（Go `AddBatch` 同款，摊薄锁竞争）。
    pub fn add_batch(&self, items: &[NameRecord]) {
        if items.is_empty() {
            return;
        }
        if let Ok(mut inner) = self.inner.write() {
            for it in items {
                let name = crate::textutil::base_name(&it.path);
                inner.insert_named(&it.path, name, it.size, it.mtime);
            }
        }
    }

    /// 批量查询路径的 size/mtime（增量扫描的变更检测依据），
    /// 全程只获取一次读锁。未命中的槽位 found[i]=false。
    pub fn lookup_batch(&self, paths: &[String]) -> (Vec<i64>, Vec<i64>, Vec<bool>) {
        let mut sizes = vec![0i64; paths.len()];
        let mut mtimes = vec![0i64; paths.len()];
        let mut found = vec![false; paths.len()];
        if let Ok(inner) = self.inner.read() {
            for (i, p) in paths.iter().enumerate() {
                if let Some(id) = inner.by_path.get(p)
                    && let Some(d) = inner.docs.get(id)
                {
                    sizes[i] = d.size;
                    mtimes[i] = d.mtime;
                    found[i] = true;
                }
            }
        }
        (sizes, mtimes, found)
    }

    /// 已索引条目数。
    pub fn count(&self) -> i64 {
        self.inner.read().map(|i| i.docs.len() as i64).unwrap_or(0)
    }

    /// 从索引移除一个路径；返回是否存在。
    pub fn remove_path(&self, path: &str) -> bool {
        let Ok(mut inner) = self.inner.write() else {
            return false;
        };
        match inner.by_path.get(path).copied() {
            Some(id) => {
                inner.remove(id);
                true
            }
            None => false,
        }
    }

    /// 仅保留 seen 中的路径，其余全部移除（增量扫描的删除检测）。
    /// 返回被移除的路径列表（供上层联动清理全文库）。
    pub fn remove_except(&self, seen: &HashSet<String>) -> Vec<String> {
        let Ok(mut inner) = self.inner.write() else {
            return Vec::new();
        };
        let victims: Vec<u32> = inner
            .docs
            .iter()
            .filter(|(_, d)| !seen.contains(&d.path))
            .map(|(id, _)| *id)
            .collect();
        let removed: Vec<String> = victims
            .iter()
            .filter_map(|id| inner.docs.get(id).map(|d| d.path.clone()))
            .collect();
        for id in victims {
            inner.remove(id);
        }
        removed
    }

    /// 清空索引（"重建索引"入口）。
    pub fn reset(&self) {
        if let Ok(mut inner) = self.inner.write() {
            inner.docs.clear();
            inner.by_path.clear();
            inner.grams.clear();
            inner.next = 0;
        }
    }

    /// 按子串查询名称，结果按"名称更短优先、修改时间更新优先"排序，
    /// 截断到 limit。
    pub fn search(&self, q: &str, limit: usize) -> Vec<FileHit> {
        let mut hits = self.search_all(q).0;
        hits.truncate(limit);
        hits
    }

    /// 返回全部命中（已排序，不截断），便于调用方统计真实命中总数
    /// 并按配额二次切片（Go `searchAll` 同款）。
    pub fn search_all(&self, q: &str) -> (Vec<FileHit>, usize) {
        let ql = crate::textutil::lower_simple(q.trim());
        if ql.is_empty() {
            return (Vec::new(), 0);
        }
        let inner = match self.inner.read() {
            Ok(i) => i,
            Err(_) => return (Vec::new(), 0),
        };
        let runes: Vec<char> = ql.chars().collect();
        let candidates = match inner.candidates_for(&runes) {
            Ok(c) => c,
            Err(()) => return (Vec::new(), 0), // 某 bigram 无命中 => 不可能子串匹配
        };

        let mut hits: Vec<FileHit> = Vec::with_capacity(32);
        for (id, doc) in inner.docs.iter() {
            if let Some(c) = &candidates
                && !c.contains(id)
            {
                continue;
            }
            if doc.lower.contains(&ql) {
                hits.push(FileHit {
                    path: doc.path.clone(),
                    name: doc.name.clone(),
                    size: doc.size,
                    mtime: doc.mtime,
                    matched: "name".to_string(),
                    kind: inner.kind.to_string(),
                    snippet: String::new(),
                });
            }
        }

        // 名称更短（字节长度）优先、修改时间更新优先（Go 同款）
        hits.sort_by(|a, b| {
            a.name
                .len()
                .cmp(&b.name.len())
                .then_with(|| b.mtime.cmp(&a.mtime))
        });
        let total = hits.len();
        (hits, total)
    }

    // ------------------------------------------------------------ 持久化

    /// 导出全部记录（快照落盘用，Go `exportRecords` 同款）。
    pub fn export_records(&self) -> Vec<NameRecord> {
        let inner = match self.inner.read() {
            Ok(i) => i,
            Err(_) => return Vec::new(),
        };
        inner
            .docs
            .values()
            .map(|d| NameRecord {
                path: d.path.clone(),
                size: d.size,
                mtime: d.mtime,
            })
            .collect()
    }

    /// 导入记录（快照恢复用，Go `importRecords` 同款：
    /// 名字取路径末段，防御性跳过空路径/空名与重复路径）。
    pub fn import_records(&self, recs: &[NameRecord]) {
        if recs.is_empty() {
            return;
        }
        if let Ok(mut inner) = self.inner.write() {
            for r in recs {
                if r.path.is_empty() {
                    continue;
                }
                let base = crate::textutil::base_name(&r.path);
                let lower = crate::textutil::lower_simple(base);
                if lower.is_empty() {
                    continue;
                }
                inner.insert_named(&r.path, base, r.size, r.mtime);
            }
        }
    }
}

// ---------------------------------------------------------------- 测试

#[cfg(test)]
mod tests {
    use super::*;

    fn rec(path: &str, size: i64, mtime: i64) -> NameRecord {
        NameRecord {
            path: path.into(),
            size,
            mtime,
        }
    }

    #[test]
    fn add_search_and_ordering() {
        let idx = NameIndex::new("file");
        idx.add("/x/longer_name.txt", 1, 100);
        idx.add("/x/短.txt", 2, 300);
        idx.add("/x/mid_name.txt", 3, 200);
        let (hits, total) = idx.search_all("name");
        assert_eq!(total, 2);
        // 名称更短优先：mid_name(12) < longer_name(15)
        assert_eq!(hits[0].name, "mid_name.txt");
        assert_eq!(hits[1].name, "longer_name.txt");
        assert_eq!(hits[0].matched, "name");
        assert_eq!(hits[0].kind, "file");
    }

    #[test]
    fn ordering_same_length_prefers_newer_mtime() {
        let idx = NameIndex::new("file");
        idx.add("/x/a.txt", 1, 100);
        idx.add("/x/b.txt", 1, 200);
        let (hits, _) = idx.search_all(".txt");
        assert_eq!(hits[0].name, "b.txt", "同长取 mtime 更新者");
    }

    #[test]
    fn case_insensitive_substring() {
        let idx = NameIndex::new("file");
        idx.add("/x/Report.PDF", 1, 1);
        assert_eq!(idx.search_all("report").1, 1);
        assert_eq!(idx.search_all("PDF").1, 1);
        // 中文 bigram
        idx.add("/x/会议纪要.docx", 1, 1);
        assert_eq!(idx.search_all("会议").1, 1);
        assert_eq!(idx.search_all("议纪").1, 1, "跨字符 bigram 命中");
        assert_eq!(idx.search_all("会要").1, 0, "非连续子串不命中");
    }

    #[test]
    fn single_char_query_scans_all() {
        let idx = NameIndex::new("file");
        idx.add("/x/a.txt", 1, 1);
        idx.add("/x/ba.txt", 1, 1);
        assert_eq!(idx.search_all("a").1, 2);
        assert_eq!(idx.search_all("中").1, 0);
        idx.add("/x/中文.txt", 1, 1);
        assert_eq!(idx.search_all("文").1, 1);
    }

    #[test]
    fn dedupe_same_path_replaces_old() {
        let idx = NameIndex::new("file");
        idx.add("/x/report.docx", 1, 1);
        idx.add("/x/report.docx", 2, 9); // 同路径重复加入
        assert_eq!(idx.count(), 1);
        let (hits, _) = idx.search_all("report");
        assert_eq!(hits[0].size, 2);
        assert_eq!(hits[0].mtime, 9);
    }

    #[test]
    fn lookup_batch_and_remove() {
        let idx = NameIndex::new("file");
        idx.add("/x/a.txt", 11, 111);
        idx.add("/x/b.txt", 22, 222);
        let (sizes, mtimes, found) = idx.lookup_batch(&["/x/a.txt".into(), "/x/none".into()]);
        assert_eq!(sizes, vec![11, 0]);
        assert_eq!(mtimes, vec![111, 0]);
        assert_eq!(found, vec![true, false]);
        assert!(idx.remove_path("/x/a.txt"));
        assert!(!idx.remove_path("/x/a.txt"));
        assert_eq!(idx.count(), 1);
    }

    #[test]
    fn remove_except_keeps_seen() {
        let idx = NameIndex::new("file");
        for p in ["/x/a", "/x/b", "/x/c"] {
            idx.add(p, 1, 1);
        }
        let seen: HashSet<String> = ["/x/a".to_string(), "/x/c".to_string()].into();
        let removed = idx.remove_except(&seen);
        assert_eq!(removed.len(), 1);
        assert_eq!(removed[0], "/x/b");
        assert_eq!(idx.count(), 2);
    }

    #[test]
    fn add_named_supports_uri_paths() {
        let idx = NameIndex::new("folder");
        idx.add_named("content://com.android.providers/term", "自定义名", 0, 5);
        let (hits, total) = idx.search_all("自定义");
        assert_eq!(total, 1);
        assert_eq!(hits[0].kind, "folder");
    }

    #[test]
    fn add_batch_single_lock_same_semantics() {
        let idx = NameIndex::new("file");
        idx.add_batch(&[
            rec("/x/one.txt", 1, 1),
            rec("/x/two.txt", 2, 2),
            rec("/x/one.txt", 3, 3), // 批内重复：后者胜
        ]);
        assert_eq!(idx.count(), 2);
        let (sizes, _, _) = idx.lookup_batch(&["/x/one.txt".into()]);
        assert_eq!(sizes[0], 3);
    }

    #[test]
    fn reset_clears_everything() {
        let idx = NameIndex::new("file");
        idx.add("/x/a.txt", 1, 1);
        idx.reset();
        assert_eq!(idx.count(), 0);
        assert_eq!(idx.search_all("a").1, 0);
        // reset 后可继续使用
        idx.add("/x/b.txt", 1, 1);
        assert_eq!(idx.search_all("b").1, 1);
    }

    #[test]
    fn export_import_roundtrip() {
        let src = NameIndex::new("folder");
        src.add("/d/中文目录", 7, 77);
        src.add("/d/sub2", 0, 42);
        let recs = src.export_records();
        let dst = NameIndex::new("folder");
        dst.import_records(&recs);
        assert_eq!(dst.count(), 2);
        assert_eq!(dst.search_all("中文").1, 1);
        let (sizes, mtimes, _) = dst.lookup_batch(&["/d/sub2".into()]);
        assert_eq!((sizes[0], mtimes[0]), (0, 42));
    }

    #[test]
    fn empty_name_and_empty_path_ignored() {
        let idx = NameIndex::new("file");
        idx.add("/x/.hidden", 1, 1); // lower 为 ".hidden"，非空，正常入册
        assert_eq!(idx.count(), 1);
    }
}
