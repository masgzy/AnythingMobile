//! 全文内容库 —— 移植 Go `core/fts.go` + `ftsquery.go`（bleve scorch → tantivy）。
//!
//! 与 Go 版的对应关系：
//! - bleve scorch → tantivy（持久化倒排 + 文档存储 + mmap）；
//! - gse 分词 → jieba（[`crate::segment`]，词典同源）；
//! - 三字段设计逐字保留：
//!   * `path`  —— 文档 ID（唯一键，删除依据；raw 索引 + 存储）；
//!   * `big`   —— unigram+bigram 索引：候选集收敛（倒排求交），
//!     邻接校验走"存储文本子串验证"而非位置向量（Go 同款，省位置数组）；
//!   * `words` —— jieba 分词索引：词边界相关性加权（排序质量）；
//!   * `text`  —— 仅存储：精确校验与命中摘要的文本来源。
//! - `id_set` 维护路径集合缓存，支撑 Has/RemoveExcept 的 O(1) 判定，
//!   打开时从索引词枚举真实重建（单一事实源是索引本身，Go 同款）。
//!
//! 降级语义（Go `broken` 同款）：底层索引打开/创建失败时所有读写降级为
//! 无害空操作，搜索返回空、Add 报错，绝不 panic。
//!
//! 查询层三阶段（Go `ftsquery.go` 精确移植）：
//! 1. bigram/unigram 倒排收敛候选集（AND 语义：任一词无候选即整体空）；
//! 2. jieba 整词命中频次（词边界加权，score = bigram 频次 + 4×整词频次）；
//! 3. 存储文本精确校验 + 摘要（按预评分降序消费，凑满预算提前收工）。

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, RwLock};

use tantivy::postings::Postings;
use tantivy::schema::{IndexRecordOption, Schema, TextFieldIndexing, TextOptions, Value};
use tantivy::tokenizer::RawTokenizer;
use tantivy::{DocAddress, DocSet, Index, IndexReader, IndexWriter, TantivyDocument, Term};

use crate::error::{EngineError, EngineResult};
use crate::segment::{CjkBigramTokenizer, JiebaTokenizer};
use crate::types::FileHit;

/// 单文档入库文本上限（1MB，超出按 rune 截断；Go `maxFTSText` 同款）。
pub(crate) const MAX_FTS_TEXT: usize = 1 << 20;
/// 词边界命中相对 bigram 命中的权重（Go `wordBonusScore` 同款）。
const WORD_BONUS_SCORE: i64 = 4;
/// 全文索引子目录名（Go `ftsDirName` 同款；旧 Go 版目录损坏时走重建路径）。
const FTS_DIR_NAME: &str = "fts";
/// 写线程与内存预算（移动端取向：小堆、少线程）。
const WRITER_THREADS: usize = 1;
const WRITER_HEAP_BYTES: usize = 24 << 20;

/// 存储文本命中的候选（排序 + 验证条目）。
struct OrderItem {
    seg: u32,
    doc: u32,
    score: i64,
    path: String,
}

/// 可替换的索引核心（Reset 整体重建时被换出；Arc 保活读端安全过渡）。
struct StoreCore {
    index: Index,
    writer: Mutex<Option<IndexWriter>>,
    reader: IndexReader,
    f_path: tantivy::schema::Field,
    f_big: tantivy::schema::Field,
    f_words: tantivy::schema::Field,
    f_text: tantivy::schema::Field,
    id_set: RwLock<HashSet<String>>,
}

pub struct ContentStore {
    core: RwLock<Option<Arc<StoreCore>>>,
    idx_dir: PathBuf,
}

impl ContentStore {
    /// 打开（或创建）持久化全文索引。
    /// `data_dir` 为空时使用临时目录（无持久化语义，供测试与降级场景）。
    pub fn new(data_dir: &str) -> Self {
        // 预热分词词典（约 150ms，一次性；Go 同款时机）
        let _ = crate::segment::shared_jieba();

        let dir = if data_dir.is_empty() {
            match tempfile_dir() {
                Some(d) => d,
                None => return Self::broken(PathBuf::from("")),
            }
        } else {
            PathBuf::from(data_dir)
        };
        let idx_dir = dir.join(FTS_DIR_NAME);

        match Self::open_or_create(&idx_dir) {
            Ok(core) => ContentStore {
                core: RwLock::new(Some(Arc::new(core))),
                idx_dir,
            },
            Err(_) => Self::broken(idx_dir),
        }
    }

    fn broken(idx_dir: PathBuf) -> Self {
        ContentStore {
            core: RwLock::new(None),
            idx_dir,
        }
    }

    /// 当前核心快照；broken 返回 None（Go `broken.Load()` 同判）。
    fn core(&self) -> Option<Arc<StoreCore>> {
        self.core.read().ok()?.clone()
    }

    pub fn is_broken(&self) -> bool {
        self.core().is_none()
    }

    /// 打开已有索引；不存在则按 schema 创建；创建失败清理目录后重试一次。
    /// 与 Go `openOrCreateFTS` 同构（tantivy 的独占锁由 writer 创建阶段获取，
    /// 被其他实例占用时报错 → 上层降级，绝不挂死）。
    fn open_or_create(idx_dir: &Path) -> EngineResult<StoreCore> {
        let (schema, f_path, f_big, f_words, f_text) = build_schema();
        let open = || -> EngineResult<Index> {
            if let Ok(idx) = Index::open_in_dir(idx_dir) {
                return Ok(idx);
            }
            // tantivy create_in_dir 要求目录已存在（bleve 自动创建，Go 同款行为）
            if std::fs::create_dir_all(idx_dir).is_err() {
                return Err(EngineError::new("core: 全文索引目录创建失败"));
            }
            match Index::create_in_dir(idx_dir, schema.clone()) {
                Ok(idx) => Ok(idx),
                Err(first_err) => {
                    // 目录损坏（半写状态等）：删除重建一次，仍失败则降级
                    let _ = std::fs::remove_dir_all(idx_dir);
                    let _ = std::fs::create_dir_all(idx_dir);
                    Index::create_in_dir(idx_dir, schema.clone()).map_err(|e| {
                        EngineError::new(format!("core: 全文索引创建失败: {first_err}/{e}"))
                    })
                }
            }
        };
        let index = open()?;
        // 自定义分词器注册（打开/创建后、任何读写前）
        index
            .tokenizers()
            .register("cjk_bigram", CjkBigramTokenizer);
        index.tokenizers().register("cjk_gse", JiebaTokenizer);
        index
            .tokenizers()
            .register("raw_path", RawTokenizer::default());

        let writer = index
            .writer_with_num_threads::<TantivyDocument>(WRITER_THREADS, WRITER_HEAP_BYTES)
            .map_err(|e| EngineError::new(format!("core: 全文索引不可用: {e}")))?;
        let reader = index
            .reader_builder()
            .reload_policy(tantivy::ReloadPolicy::Manual)
            .try_into()
            .map_err(|e| EngineError::new(format!("core: 全文索引 reader 失败: {e}")))?;

        let core = StoreCore {
            index,
            writer: Mutex::new(Some(writer)),
            reader,
            f_path,
            f_big,
            f_words,
            f_text,
            id_set: RwLock::new(HashSet::new()),
        };
        Self::reload_id_set_impl(&core);
        Ok(core)
    }

    // ------------------------------------------------------------ 写通道

    /// 写入/更新一个文档的全文。空正文返回错误（与 Go 语义一致）。
    pub fn add(&self, path: &str, text: &str) -> EngineResult<()> {
        if path.is_empty() {
            return Err(EngineError::new("core: path 为空"));
        }
        if self.is_broken() {
            return Err(EngineError::new("core: 全文索引不可用"));
        }
        let normalized = crate::textutil::normalize_for_store(text);
        if normalized.trim().is_empty() {
            return Err(EngineError::new("core: 文档正文为空"));
        }
        let t = crate::textutil::truncate_runes(&normalized, MAX_FTS_TEXT);
        if t.is_empty() {
            return Err(EngineError::new("core: 文档正文为空"));
        }
        let n = self.add_batch(&[(path.to_string(), t.to_string())]);
        if n == 0 {
            return Err(EngineError::new("core: 全文索引不可用"));
        }
        Ok(())
    }

    /// 批量写入：单次 commit 提交。全量建索引时数千文档逐条提交会把
    /// 段生成/合并开销放大数十倍（Go alpha16 攒批优化同款）。任意环节
    /// 整体失败时回退逐条 Add，隔离坏文档；返回成功数。
    /// 同路径重复时后者覆盖前者（Go/bleve 语义一致）。
    pub fn add_batch(&self, docs: &[(String, String)]) -> usize {
        if self.is_broken() || docs.is_empty() {
            return 0;
        }
        // 预处理与 add 逐条一致：归一化 → 空判断 → 截断；同路径去重保尾
        let mut prepared: Vec<(String, String)> = Vec::with_capacity(docs.len());
        let mut seen: HashMap<&str, usize> = HashMap::with_capacity(docs.len());
        for (path, text) in docs {
            let t = crate::textutil::normalize_for_store(text);
            if t.trim().is_empty() {
                continue;
            }
            let t = crate::textutil::truncate_runes(&t, MAX_FTS_TEXT).to_string();
            if t.is_empty() {
                continue;
            }
            if let Some(i) = seen.get(path.as_str()) {
                prepared[*i].1 = t;
                continue;
            }
            seen.insert(path, prepared.len());
            prepared.push((path.clone(), t));
        }
        if prepared.is_empty() {
            return 0;
        }

        let core = match self.core() {
            Some(c) => c,
            None => return 0,
        };
        if self.commit_batch(&core, &prepared) {
            let mut ids = core.id_set.write().unwrap();
            for (p, _) in &prepared {
                ids.insert(p.clone());
            }
            return prepared.len();
        }
        // 整批失败：回退逐条隔离坏文档（Go addOneByOne 同款）
        let mut ok = 0;
        for (p, t) in &prepared {
            if self.commit_batch(&core, &[(p.clone(), t.clone())]) {
                core.id_set.write().unwrap().insert(p.clone());
                ok += 1;
            }
        }
        ok
    }

    /// 单次 commit 提交一批 upsert（delete+add）。
    fn commit_batch(&self, core: &StoreCore, batch: &[(String, String)]) -> bool {
        let mut guard = match core.writer.lock() {
            Ok(g) => g,
            Err(_) => return false,
        };
        let Some(writer) = guard.as_mut() else {
            return false; // 已关闭/重置中
        };
        let mut ok = true;
        for (path, text) in batch {
            writer.delete_term(Term::from_field_text(core.f_path, path));
            let mut doc = TantivyDocument::new();
            doc.add_text(core.f_path, path.as_str());
            doc.add_text(core.f_big, text.as_str());
            doc.add_text(core.f_words, text.as_str());
            doc.add_text(core.f_text, text.as_str());
            if writer.add_document(doc).is_err() {
                ok = false;
            }
        }
        if ok && writer.commit().is_err() {
            ok = false;
        }
        if ok {
            let _ = core.reader.reload();
        }
        ok
    }

    /// 移除一个文档；返回是否存在。
    pub fn remove(&self, path: &str) -> bool {
        self.remove_batch(std::slice::from_ref(&path.to_string())) > 0
    }

    /// 批量移除多个文档：单次 commit 提交（alpha26 增量优化 —— 变更文档
    /// 在旧实现里逐条 remove，每个文档都触发一次 tantivy commit +
    /// reader.reload，多文档变更时提交次数线性放大，Android flash 上
    /// 单次 commit 可达百毫秒级）。返回实际移除的路径数；commit 失败
    /// 仍按移除计数返回（与 remove/remove_except 语义一致）。
    pub fn remove_batch(&self, paths: &[String]) -> usize {
        if paths.is_empty() {
            return 0;
        }
        let core = match self.core() {
            Some(c) => c,
            None => return 0,
        };
        let victims: Vec<String> = {
            let mut ids = match core.id_set.write() {
                Ok(g) => g,
                Err(_) => return 0,
            };
            paths.iter().filter(|p| ids.remove(*p)).cloned().collect()
        };
        if victims.is_empty() {
            return 0;
        }
        if !self.commit_deletes(&core, victims.iter().cloned()) {
            return victims.len();
        }
        victims.len()
    }

    /// 仅保留 seen 中的文档，其余移除；返回被移除的路径数。
    pub fn remove_except(&self, seen: &HashSet<String>) -> usize {
        let core = match self.core() {
            Some(c) => c,
            None => return 0,
        };
        let victims: Vec<String> = {
            let ids = core.id_set.read().unwrap();
            ids.iter().filter(|p| !seen.contains(*p)).cloned().collect()
        };
        if victims.is_empty() {
            return 0;
        }
        {
            let mut ids = core.id_set.write().unwrap();
            for p in &victims {
                ids.remove(p);
            }
        }
        if !self.commit_deletes(&core, victims.iter().cloned()) {
            return victims.len(); // 与 Go 一致：删除批次失败仍按移除计数返回
        }
        victims.len()
    }

    fn commit_deletes(&self, core: &StoreCore, paths: impl Iterator<Item = String>) -> bool {
        let mut guard = match core.writer.lock() {
            Ok(g) => g,
            Err(_) => return false,
        };
        let Some(writer) = guard.as_mut() else {
            return false;
        };
        for p in paths {
            writer.delete_term(Term::from_field_text(core.f_path, &p));
        }
        let ok = writer.commit().is_ok();
        if ok {
            let _ = core.reader.reload();
        }
        ok
    }

    /// 全文库中是否已存在该路径的正文（增量扫描补解析判据）。
    pub fn has(&self, path: &str) -> bool {
        self.core()
            .map(|c| c.id_set.read().map(|s| s.contains(path)).unwrap_or(false))
            .unwrap_or(false)
    }

    /// 已收录文档数。
    pub fn count(&self) -> i64 {
        self.core()
            .map(|c| c.id_set.read().map(|s| s.len() as i64).unwrap_or(0))
            .unwrap_or(0)
    }

    /// 清空并重建索引（"重建索引"入口）。
    pub fn reset(&self) {
        if self.is_broken() {
            return;
        }
        // 独占锁：换出旧核心（持有旧 Arc 的读端安全过渡，段文件延迟释放）
        let mut guard = match self.core.write() {
            Ok(g) => g,
            Err(_) => return,
        };
        if let Some(old) = guard.take() {
            // 等待后台合并线程退出后再删目录，避免 Windows/Android 文件占用
            if let Ok(mut w) = old.writer.lock()
                && let Some(writer) = w.take()
            {
                let _ = writer.wait_merging_threads();
            }
        }
        let _ = std::fs::remove_dir_all(&self.idx_dir);
        *guard = match Self::open_or_create(&self.idx_dir) {
            Ok(core) => Some(Arc::new(core)),
            Err(_) => None,
        };
    }

    /// 关闭底层索引（进程退出前或测试收尾调用；此后对象不可再用）。
    pub fn close(&self) {
        if let Ok(mut guard) = self.core.write()
            && let Some(old) = guard.take()
            && let Ok(mut w) = old.writer.lock()
            && let Some(writer) = w.take()
        {
            let _ = writer.wait_merging_threads();
        }
    }

    /// 从索引词枚举全部文档 ID（启动时的单一事实源校准，Go `reloadIDSet`
    /// 同语义：path 字段每个词条即一个文档路径）。
    fn reload_id_set_impl(core: &StoreCore) {
        let searcher = core.reader.searcher();
        let mut set: HashSet<String> = HashSet::with_capacity(1024);
        for seg in searcher.segment_readers() {
            let Ok(inv) = seg.inverted_index(core.f_path) else {
                continue;
            };
            let Ok(mut stream) = inv.terms().stream() else {
                continue;
            };
            while stream.advance() {
                if let Ok(s) = std::str::from_utf8(stream.key()) {
                    set.insert(s.to_string());
                }
            }
        }
        *core.id_set.write().unwrap() = set;
    }

    // ------------------------------------------------------------ 查询通道

    /// 返回命中（按相关性降序，截断到 verify_cap）与命中总数。
    /// verify_cap <= 0 表示不设上限（全量验证；兼容旧调用方与测试）。
    ///
    /// 性能模型（Go alpha16 同款）：
    /// - ≤2 rune 词的候选集即精确命中：unigram/bigram 倒排命中 ⟺ 该词是
    ///   归一化文本的精确子串，total 恒精确；验证阶段只为取摘要与词边界
    ///   加分，只对前 verify_cap 个候选读取存储正文；
    /// - ≥3 rune 词存在跨 bigram 假阳性，需逐文档读正文校验：候选数
    ///   超预算时按预评分降序验证、凑满即停，total 为已验证数（下界）。
    pub fn search_all(&self, q: &str, verify_cap: usize) -> (Vec<FileHit>, usize) {
        let Some(core) = self.core() else {
            return (Vec::new(), 0);
        };
        let ql = crate::textutil::lower_simple(&crate::textutil::normalize_for_store(q.trim()));
        if ql.is_empty() {
            return (Vec::new(), 0);
        }
        let terms: Vec<&str> = ql.split_whitespace().collect();
        if terms.is_empty() {
            return (Vec::new(), 0);
        }

        let searcher = core.reader.searcher();

        // ---- 第一阶段：bigram/unigram 倒排收敛候选集 ----
        // cand: (seg, doc) -> Σ(各词各 bigram 频次)
        let mut cand: HashMap<(u32, u32), i64> = HashMap::new();
        for term in &terms {
            let next = candidates_for_term(&searcher, core.f_big, term);
            if next.is_empty() {
                return (Vec::new(), 0); // 任一词无候选 => AND 不可能命中
            }
            if cand.is_empty() {
                cand = next;
                continue;
            }
            let keys: Vec<(u32, u32)> = cand.keys().copied().collect();
            for k in keys {
                match next.get(&k) {
                    Some(nf) => {
                        let f = cand[&k];
                        cand.insert(k, f + nf);
                    }
                    None => {
                        cand.remove(&k);
                    }
                }
            }
            if cand.is_empty() {
                return (Vec::new(), 0);
            }
        }

        // ---- 第二阶段：jieba 整词命中频次（词边界加权）----
        let word_freq = word_frequencies(&searcher, core.f_words, &ql);

        // ---- 预评分排序：score = bigram 频次 + 4×整词频次（Go 同款）。
        // 与验证后的最终评分一致，该序即最终序；提前终止按此序消费。----
        let candidate_exact = terms.iter().all(|t| t.chars().count() <= 2);
        let mut order: Vec<OrderItem> = Vec::with_capacity(cand.len());
        for (key, freq) in &cand {
            let doc_addr = DocAddress {
                segment_ord: key.0,
                doc_id: key.1,
            };
            let path = match searcher.doc::<TantivyDocument>(doc_addr) {
                Ok(d) => d
                    .get_first(core.f_path)
                    .and_then(|v| v.as_str().map(|s| s.to_string())),
                Err(_) => None,
            };
            let Some(path) = path else { continue };
            let score = freq + word_freq.get(key).copied().unwrap_or(0) * WORD_BONUS_SCORE;
            order.push(OrderItem {
                seg: key.0,
                doc: key.1,
                score,
                path,
            });
        }
        if order.is_empty() {
            return (Vec::new(), 0);
        }
        order.sort_by(|a, b| b.score.cmp(&a.score).then_with(|| a.path.cmp(&b.path)));

        // ---- 验证预算：候选数不超预算 => 全量验证（total 精确）；
        // 超预算 => 只验证预评分最高的前 verify_cap 个 ----
        let verify_cap = if verify_cap > 0 && verify_cap < order.len() {
            verify_cap
        } else {
            order.len()
        };

        // ---- 第三阶段：存储文本精确校验 + 摘要（按预评分降序消费，
        // 凑满即提前收工；score 不因验证改变，该序即最终序）----
        let mut results: Vec<(FileHit, i64, String)> = Vec::new();
        for it in &order {
            if results.len() >= verify_cap {
                break;
            }
            let doc_addr = DocAddress {
                segment_ord: it.seg,
                doc_id: it.doc,
            };
            let text = match searcher.doc::<TantivyDocument>(doc_addr) {
                Ok(d) => d
                    .get_first(core.f_text)
                    .and_then(|v| v.as_str().map(String::from)),
                Err(_) => None,
            };
            let Some(text) = text else { continue };
            let tl = crate::textutil::lower_simple(&text);
            let mut first_off: isize = -1;
            let mut verified = true;
            for term in &terms {
                let off = find_substring(&tl, term);
                match off {
                    Some(off) => {
                        if first_off < 0 {
                            first_off = off as isize;
                        }
                    }
                    None => {
                        verified = false;
                        break;
                    }
                }
            }
            if !verified {
                continue;
            }
            // 大小写转换改变字节长度（土耳其 İ 类）：偏移映射不到原文，
            // 退化为对小写文本取摘要（Go 同款）
            let src = if tl.len() != text.len() {
                tl.clone()
            } else {
                text.clone()
            };
            let snippet = snippet(&src, first_off.max(0) as usize, terms[0].len());
            results.push((
                FileHit {
                    path: it.path.clone(),
                    name: crate::textutil::base_name(&it.path).to_string(),
                    size: 0,
                    mtime: 0,
                    matched: "content".to_string(),
                    kind: "file".to_string(),
                    snippet,
                },
                it.score,
                it.path.clone(),
            ));
        }

        let total = if candidate_exact {
            order.len()
        } else {
            results.len()
        };

        results.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.2.cmp(&b.2)));
        results.truncate(verify_cap);
        let hits = results.into_iter().map(|(h, _, _)| h).collect();
        (hits, total)
    }
}

impl Drop for ContentStore {
    fn drop(&mut self) {
        // 尽力关闭；等待合并线程退出避免资源悬挂（Go Close 幂等语义）
        if let Ok(mut guard) = self.core.write()
            && let Some(core) = guard.take()
            && let Ok(mut w) = core.writer.lock()
            && let Some(writer) = w.take()
        {
            let _ = writer.wait_merging_threads();
        }
    }
}

// ---------------------------------------------------------------- 内部工具

fn tempfile_dir() -> Option<PathBuf> {
    let base = std::env::temp_dir();
    for i in 0..64 {
        let d = base.join(format!("anything-fts-{}-{}", std::process::id(), i));
        if std::fs::create_dir(&d).is_ok() {
            return Some(d);
        }
    }
    None
}

fn build_schema() -> (
    Schema,
    tantivy::schema::Field,
    tantivy::schema::Field,
    tantivy::schema::Field,
    tantivy::schema::Field,
) {
    let mut sb = Schema::builder();
    let f_path = sb.add_text_field(
        "path",
        TextOptions::default().set_stored().set_indexing_options(
            TextFieldIndexing::default()
                .set_tokenizer("raw_path")
                .set_index_option(IndexRecordOption::Basic),
        ),
    );
    // 邻接校验走"存储文本子串验证"而非位置向量：关闭位置可省去海量
    // 位置数组（长文档 OOM 风险），倒排仅存词频，候选集收敛不受影响。
    let f_big = sb.add_text_field(
        "big",
        TextOptions::default().set_indexing_options(
            TextFieldIndexing::default()
                .set_tokenizer("cjk_bigram")
                .set_index_option(IndexRecordOption::WithFreqs),
        ),
    );
    let f_words = sb.add_text_field(
        "words",
        TextOptions::default().set_indexing_options(
            TextFieldIndexing::default()
                .set_tokenizer("cjk_gse")
                .set_index_option(IndexRecordOption::WithFreqs),
        ),
    );
    let f_text = sb.add_text_field("text", TextOptions::default().set_stored());
    let schema = sb.build();
    (schema, f_path, f_big, f_words, f_text)
}

/// 单个词的候选集：unigram 直查或 bigram 交集（Go `candidatesForTerm` 同款）。
fn candidates_for_term(
    searcher: &tantivy::Searcher,
    field: tantivy::schema::Field,
    term: &str,
) -> HashMap<(u32, u32), i64> {
    let runes: Vec<char> = term.chars().collect();
    if runes.is_empty() {
        return HashMap::new();
    }
    if runes.len() == 1 {
        return collect_postings(searcher, field, term);
    }
    let mut out: HashMap<(u32, u32), i64> = HashMap::new();
    for i in 0..=runes.len() - 2 {
        let gram: String = runes[i..i + 2].iter().collect();
        let next = collect_postings(searcher, field, &gram);
        if next.is_empty() {
            return HashMap::new();
        }
        if out.is_empty() {
            out = next;
            continue;
        }
        let keys: Vec<(u32, u32)> = out.keys().copied().collect();
        for k in keys {
            match next.get(&k) {
                Some(nf) => {
                    let f = out[&k];
                    out.insert(k, f + nf);
                }
                None => {
                    out.remove(&k);
                }
            }
        }
        if out.is_empty() {
            return HashMap::new();
        }
    }
    out
}

/// 枚举一个 term 的倒排（Go `collectPostings` 同款：全量收集 freq）。
fn collect_postings(
    searcher: &tantivy::Searcher,
    field: tantivy::schema::Field,
    term_text: &str,
) -> HashMap<(u32, u32), i64> {
    let term = Term::from_field_text(field, term_text);
    let mut out = HashMap::new();
    for (seg_ord, seg) in searcher.segment_readers().iter().enumerate() {
        let Ok(inv) = seg.inverted_index(field) else {
            continue;
        };
        let Ok(Some(mut postings)) = inv.read_postings(&term, IndexRecordOption::WithFreqs) else {
            continue;
        };
        // tantivy 0.27 语义：read_postings 返回的游标初始即定位在首个文档，
        // 先 advance() 会跳过它 —— 正确模式是 doc() → 处理 → advance()。
        let mut doc = postings.doc();
        while doc != tantivy::TERMINATED {
            let freq = postings.term_freq() as i64;
            out.insert((seg_ord as u32, doc), freq);
            doc = postings.advance();
        }
    }
    out
}

/// 查询词经 jieba 切词后，各整词在 words 字段的命中频次合计
/// （Go `wordFrequencies` 同款：长度 ≥2 的词才参与加权）。
fn word_frequencies(
    searcher: &tantivy::Searcher,
    field: tantivy::schema::Field,
    ql: &str,
) -> HashMap<(u32, u32), i64> {
    let Some(jieba) = crate::segment::shared_jieba() else {
        return HashMap::new();
    };
    let tokens: Vec<String> = jieba
        .cut(ql, false)
        .into_iter()
        .map(|t| t.word.trim().to_string())
        .filter(|w| !w.is_empty() && w.chars().count() >= 2)
        .collect();
    let mut out: HashMap<(u32, u32), i64> = HashMap::new();
    for tok in tokens {
        for (k, f) in collect_postings(searcher, field, &tok) {
            *out.entry(k).or_insert(0) += f;
        }
    }
    out
}

/// 生成命中位置前后的摘要（前后各取约 40 字节）。
/// 切片必须对齐 UTF-8 字符边界：start 回退到 rune 起点、end 前扩到
/// rune 边界，否则半个多字节序列在 UI 上渲染为 U+FFFD（菱形）。
pub fn snippet(text: &str, idx: usize, q_len: usize) -> String {
    const CTX: usize = 40;
    let (idx, q_len) = if idx > text.len() || idx + q_len > text.len() {
        // 偏移异常（不应发生）：退化为开头截取，保证不越界
        (0usize, 0usize)
    } else {
        (idx, q_len)
    };
    let mut start = idx.saturating_sub(CTX);
    if start > 0 {
        while start < idx && !text.is_char_boundary(start) {
            start += 1;
        }
    }
    let mut end = (idx + q_len + CTX).min(text.len());
    while end < text.len() && !text.is_char_boundary(end) {
        end += 1;
    }
    let mut s = text[start..end].replace('\n', " ");
    s = s.trim().to_string();
    if start > 0 {
        s.insert(0, '…');
    }
    if end < text.len() {
        s.push('…');
    }
    s
}

/// Go `strings.Index` 等价：子串查找（字节偏移）。
fn find_substring(haystack: &str, needle: &str) -> Option<usize> {
    haystack.find(needle)
}

/// 供引擎层复用的搜索入口包装（Go Engine 直接调 content.searchAll）。
pub type SharedContentStore = Arc<ContentStore>;

// ---------------------------------------------------------------- 测试

#[cfg(test)]
mod tests {
    use super::*;

    fn store(tmp: &Path) -> ContentStore {
        let s = ContentStore::new(tmp.to_str().unwrap());
        assert!(!s.is_broken(), "临时目录索引不应失败");
        s
    }

    #[test]
    fn add_search_basic_substring() {
        let tmp = tempfile::tempdir().unwrap();
        let s = store(tmp.path());
        assert!(
            s.add("/a/报告.docx", "项目会议纪要：预算为 120 万元")
                .is_ok()
        );
        assert!(s.add("/b/notes.txt", "buy milk tomorrow").is_ok());

        // 中文子串（bigram 倒排 + 邻接验证）
        let (hits, total) = s.search_all("会议纪要", 0);
        assert_eq!(total, 1);
        assert_eq!(hits[0].path, "/a/报告.docx");
        assert_eq!(hits[0].matched, "content");
        assert!(hits[0].snippet.contains("会议纪要"));

        // 英文大小写不敏感
        let (hits, total) = s.search_all("MILK", 0);
        assert_eq!(total, 1);
        assert_eq!(hits[0].path, "/b/notes.txt");

        // 无命中
        assert_eq!(s.search_all("不存在的词组合xyz", 0).1, 0);
    }

    #[test]
    fn cross_bigram_false_positive_filtered() {
        let tmp = tempfile::tempdir().unwrap();
        let s = store(tmp.path());
        // "会议…纪要"：各 bigram 命中但位置不相邻 —— 必须被精确校验剔除
        s.add("/a/x.txt", "会议已延期，纪要另发").unwrap();
        assert_eq!(s.search_all("会议纪要", 0).1, 0, "跨 bigram 假阳性应被剔除");
        s.add("/b/y.txt", "会议纪要已发").unwrap();
        let (hits, total) = s.search_all("会议纪要", 0);
        assert_eq!(total, 1);
        assert_eq!(hits[0].path, "/b/y.txt");
    }

    #[test]
    fn multi_term_and_semantics() {
        let tmp = tempfile::tempdir().unwrap();
        let s = store(tmp.path());
        s.add("/a/1.txt", "合同 编号 甲乙丙").unwrap();
        s.add("/b/2.txt", "只有合同没有清单").unwrap();
        let (hits, total) = s.search_all("合同 编号", 0);
        assert_eq!(total, 1);
        assert_eq!(hits[0].path, "/a/1.txt");
    }

    #[test]
    fn upsert_and_remove_and_has() {
        let tmp = tempfile::tempdir().unwrap();
        let s = store(tmp.path());
        s.add("/a/doc.txt", "旧内容 alpha").unwrap();
        assert!(s.has("/a/doc.txt"));
        s.add("/a/doc.txt", "新内容 beta").unwrap(); // upsert
        assert_eq!(s.search_all("alpha", 0).1, 0, "旧正文应被覆盖");
        assert_eq!(s.search_all("beta", 0).1, 1);
        assert_eq!(s.count(), 1);

        assert!(s.remove("/a/doc.txt"));
        assert!(!s.has("/a/doc.txt"));
        assert!(!s.remove("/a/doc.txt"), "重复删除返回 false");
        assert_eq!(s.count(), 0);
    }

    #[test]
    fn add_batch_dedupe_keep_last_and_blank_skip() {
        let tmp = tempfile::tempdir().unwrap();
        let s = store(tmp.path());
        let n = s.add_batch(&[
            ("/a/1.txt".into(), "旧版甲乙丙".into()),
            ("/a/1.txt".into(), "新版丁戊己".into()), // 同路径保尾（upsert）
            ("/a/2.txt".into(), "   ".into()),        // 空白：跳过
        ]);
        assert_eq!(n, 1);
        assert_eq!(s.search_all("新版丁戊己", 0).1, 1);
        assert_eq!(s.search_all("甲乙丙", 0).1, 0, "旧文本被同路径后者覆盖");
    }

    #[test]
    fn remove_except_keeps_seen() {
        let tmp = tempfile::tempdir().unwrap();
        let s = store(tmp.path());
        s.add("/a/1.txt", "aaa bbb ccc").unwrap();
        s.add("/a/2.txt", "ddd eee fff").unwrap();
        s.add("/a/3.txt", "ggg hhh iii").unwrap();
        let seen: HashSet<String> = ["/a/1.txt".to_string()].into();
        assert_eq!(s.remove_except(&seen), 2);
        assert_eq!(s.count(), 1);
        assert_eq!(s.search_all("bbb", 0).1, 1);
        assert_eq!(s.search_all("eee", 0).1, 0);
    }

    #[test]
    fn reset_clears_everything() {
        let tmp = tempfile::tempdir().unwrap();
        let s = store(tmp.path());
        s.add("/a/1.txt", "重置前内容").unwrap();
        s.reset();
        assert_eq!(s.count(), 0);
        assert_eq!(s.search_all("重置前", 0).1, 0);
        // reset 后可继续使用
        s.add("/a/2.txt", "重置后内容").unwrap();
        assert_eq!(s.search_all("重置后", 0).1, 1);
    }

    #[test]
    fn persistence_reopen_recovers_idset_and_text() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().join("data");
        let text = "持久化验证正文：全球唯一标识符";
        {
            let s = ContentStore::new(dir.to_str().unwrap());
            s.add("/x/persist.txt", text).unwrap();
        }
        // 重新打开：id_set 与全文均可恢复
        let s2 = ContentStore::new(dir.to_str().unwrap());
        assert!(!s2.is_broken());
        assert!(s2.has("/x/persist.txt"), "重开后 id_set 应从索引重建");
        let (hits, total) = s2.search_all("唯一标识符", 0);
        assert_eq!(total, 1);
        assert_eq!(hits[0].path, "/x/persist.txt");
    }

    #[test]
    fn ranking_prefers_word_boundary_hits() {
        // Go TestFTSWordBoundaryRanking 同款数据：整词高频文档排在前
        let tmp = tempfile::tempdir().unwrap();
        let s = store(tmp.path());
        s.add("/a/合同.txt", &"签署合同需要注意条款。".repeat(5))
            .unwrap();
        s.add("/b/汇总.txt", "单位面积合同量同比下降。").unwrap();
        let (hits, total) = s.search_all("合同", 0);
        assert_eq!(total, 2);
        assert_eq!(hits[0].path, "/a/合同.txt", "整词高频文档应排在前");
    }

    #[test]
    fn verify_cap_limits_results_but_reports_total() {
        let tmp = tempfile::tempdir().unwrap();
        let s = store(tmp.path());
        for i in 0..30 {
            let _ = s.add(
                &format!("/d/f{i:02}.txt"),
                &format!("公共词出现第{i}篇 正文公共词"),
            );
        }
        let (hits, total) = s.search_all("公共词", 10);
        // "公共词"为 3 rune 长词：候选超预算时按预评分序验证、凑满即停，
        // total 为已验证数（下界，Go alpha16 同语义）
        assert_eq!(total, 10);
        assert_eq!(hits.len(), 10);
        // ≤2 rune 短词：候选集即精确命中，total 不受 cap 影响
        let (_, total_short) = s.search_all("公共", 5);
        assert_eq!(total_short, 30, "短词 total 恒精确");
    }

    #[test]
    fn snippet_utf8_boundary_and_ellipsis() {
        let long = format!("{}目标词{}", "汉".repeat(50), "字".repeat(50));
        let idx = long.find("目标词").unwrap();
        let snip = snippet(&long, idx, "目标词".len());
        assert!(snip.starts_with('…') && snip.ends_with('…'));
        assert!(!snip.contains('\u{FFFD}'), "摘要不得含半个多字节序列");
        // 短文本无省略号
        assert_eq!(snippet("目标词", 0, 9), "目标词");
    }

    #[test]
    fn empty_and_invalid_inputs() {
        let tmp = tempfile::tempdir().unwrap();
        let s = store(tmp.path());
        assert!(s.add("", "x").is_err());
        assert!(s.add("/a.txt", "   ").is_err());
        assert_eq!(s.search_all("   ", 0).1, 0);
    }

    #[test]
    fn oversize_text_truncated_not_rejected() {
        let tmp = tempfile::tempdir().unwrap();
        let s = store(tmp.path());
        let big = "长".repeat(MAX_FTS_TEXT); // 3 字节/char，远超上限
        s.add("/a/big.txt", &big).unwrap();
        // 截断后仍可搜到（前 MAX_FTS_TEXT 字节内的词）
        let (hits, _) = s.search_all("长长长", 0);
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].path, "/a/big.txt");
    }
}
