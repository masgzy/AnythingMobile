//! JSON 契约类型 —— 与 Go 版（core/ 包）结构体的 JSON tag 严格一一对应。
//!
//! 契约铁律：字段名、类型、可缺省性任何一项变更 = ABI 不兼容，
//! 必须同步 bump `crate::ABI_VERSION` 并更新宿主（Kotlin）解析代码。
//! 对应关系（Go → 本文件）：
//!   core.ScanOptions   → [`ScanOptions`]
//!   core.Stats         → [`Stats`]
//!   core.SearchResponse→ [`SearchResponse`]
//!   core.FileHit       → [`FileHit`]
//!   core.IngestEntry   → [`IngestEntry`]
//!   core.ExternalFeed  → [`ExternalFeed`]

use serde::{Deserialize, Serialize};

use crate::error::{EngineError, EngineResult};

// ---------------------------------------------------------------- ScanOptions

/// `Engine.startScan(optionsJson)` 的入参。
///
/// 兼容规则与 Go `parseScanOptions` 一致：
/// - 对象形式 `{"roots":[...],"mode":"..."}`；
/// - 旧版纯数组形式 `["/path"]` 视为 `mode = "full"`；
/// - `mode` 缺省/非法值 → `"incremental"`（full/incremental 之外一律归一化）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ScanOptions {
    pub roots: Vec<String>,
    pub mode: String,
}

/// 兼容旧版数组格式的中间反序列化载体。
#[derive(Deserialize)]
struct ScanOptionsLoose {
    #[serde(default)]
    roots: Vec<String>,
    #[serde(default)]
    mode: String,
}

impl ScanOptions {
    /// 解析扫描入参，含旧版数组兼容与 mode 归一化。
    pub fn parse(json: &str) -> EngineResult<Self> {
        let trimmed = json.trim();
        if trimmed.starts_with('[') {
            let roots: Vec<String> = serde_json::from_str(trimmed).map_err(|_| {
                EngineError::new(r#"core: optionsJSON 非法，应为 {"roots":[...]} 或 ["/path"]"#)
            })?;
            return Ok(ScanOptions {
                roots,
                mode: "full".to_string(),
            });
        }
        let loose: ScanOptionsLoose = serde_json::from_str(trimmed).map_err(|_| {
            EngineError::new(r#"core: optionsJSON 非法，应为 {"roots":[...],"mode":"..."}"#)
        })?;
        let mode = if loose.mode == "full" {
            "full".to_string()
        } else {
            // 与 Go 一致：full/incremental 之外一律按 incremental 处理
            "incremental".to_string()
        };
        Ok(ScanOptions {
            roots: loose.roots,
            mode,
        })
    }
}

// ---------------------------------------------------------------- Stats

/// 一次扫描/索引的统计信息（Go `core.Stats` 同款字段）。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(default)]
pub struct Stats {
    /// 本次遍历的文件总数（本轮计数器，未扫描时为 0）。
    pub files: i64,
    /// 索引内现存文件条目数（与 first_build 判定同口径，含快照恢复）。
    /// 宿主在"尚未扫描"时只能用它判断是否首次使用。
    pub indexed: i64,
    pub added: i64,
    pub updated: i64,
    pub removed: i64,
    /// 可解析文档总数。
    #[serde(rename = "docs_found")]
    pub docs_found: i64,
    /// 已建立全文索引的文档数。
    #[serde(rename = "docs_indexed")]
    pub docs_indexed: i64,
    /// 特权通道（Shizuku/Stellar）收录的外部条目数。
    pub external: i64,
    #[serde(rename = "duration_ms")]
    pub duration_ms: i64,
    pub cancelled: bool,
    /// 本次是否为首次建索引（索引从空开始）。
    #[serde(rename = "first_build")]
    pub first_build: bool,
    /// RFC3339（Go time.RFC3339 同款语义，本地时区优先，失败退 UTC）。
    #[serde(rename = "finished_at")]
    pub finished_at: String,
}

// ---------------------------------------------------------------- 搜索

/// 一条搜索结果（Go `core.FileHit` 同款字段）。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct FileHit {
    pub path: String,
    pub name: String,
    pub size: i64,
    /// 修改时间（unix 毫秒）。
    pub mtime: i64,
    /// `"name" | "content"`。
    pub matched: String,
    /// `"file" | "folder"`。
    pub kind: String,
    /// 内容命中时的摘要，名称命中为空。
    pub snippet: String,
}

/// `Engine.search` 的返回结构（Go `core.SearchResponse` 同款字段）。
///
/// Total 为本次返回的条目数；TotalMatches 及三个分类总数为索引内的
/// 真实命中数（不受返回上限影响），供 UI 展示"命中 N 项"的真实计数。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(default)]
pub struct SearchResponse {
    pub query: String,
    #[serde(rename = "elapsed_ms")]
    pub elapsed_ms: i64,
    pub total: i64,
    #[serde(rename = "total_matches")]
    pub total_matches: i64,
    #[serde(rename = "total_files")]
    pub total_files: i64,
    #[serde(rename = "total_dirs")]
    pub total_dirs: i64,
    #[serde(rename = "total_content")]
    pub total_content: i64,
    pub hits: Vec<FileHit>,
}

// ---------------------------------------------------------------- 注入通道

/// `Engine.ingest(entriesJson)` 的条目结构（Go `core.IngestEntry` 同款字段）。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct IngestEntry {
    /// 物理路径（可映射时）或 content URI。
    pub path: String,
    /// 显示名（URI 路径时取不到真名）。
    pub name: String,
    pub size: i64,
    pub mtime: i64,
    /// `"file" | "folder"`。
    pub kind: String,
    /// 可选全文（宿主经 ContentResolver 读取并解析的文本）；空表示不进全文库。
    #[serde(default)]
    pub content: String,
}

/// `Engine.replaceExternalEntries(feedJson)` 的入参（Go `core.ExternalFeed` 同款字段）。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ExternalFeed {
    /// 枚举根目录（如 /storage/emulated/0/Android/data）。
    pub root: String,
    /// 目录清单（含根自身）。
    #[serde(default)]
    pub dirs: Vec<String>,
    /// 文件清单。
    #[serde(default)]
    pub files: Vec<String>,
}

/// `Engine.removePaths` 的返回：`{"removed": n}`。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RemoveResult {
    pub removed: i64,
}

// ---------------------------------------------------------------- 时间

/// 当前时间（unix 毫秒）。
pub fn now_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// RFC3339 当前时间：本地时区优先（与 Go `time.Now().Format(time.RFC3339)`
/// 同语义），取不到本地偏移（Android 上偶发）退 UTC；秒级精度。
pub fn rfc3339_now() -> String {
    let now = time::OffsetDateTime::now_local().unwrap_or_else(|_| time::OffsetDateTime::now_utc());
    let now = now.replace_nanosecond(0).unwrap_or(now);
    now.format(&time::format_description::well_known::Rfc3339)
        .unwrap_or_default()
}

// ---------------------------------------------------------------- 测试

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scan_options_object_form() {
        let o = ScanOptions::parse(r#"{"roots":["/a","/b"],"mode":"full"}"#).unwrap();
        assert_eq!(o.roots, vec!["/a", "/b"]);
        assert_eq!(o.mode, "full");
    }

    #[test]
    fn scan_options_legacy_array_is_full() {
        let o = ScanOptions::parse(r#"["/storage/emulated/0"]"#).unwrap();
        assert_eq!(o.roots, vec!["/storage/emulated/0"]);
        assert_eq!(o.mode, "full");
    }

    #[test]
    fn scan_options_mode_normalized() {
        let o = ScanOptions::parse(r#"{"roots":["/a"],"mode":"weird"}"#).unwrap();
        assert_eq!(o.mode, "incremental");
        let o = ScanOptions::parse(r#"{"roots":["/a"]}"#).unwrap();
        assert_eq!(o.mode, "incremental");
    }

    #[test]
    fn scan_options_rejects_bad_json() {
        assert!(ScanOptions::parse("{bad").is_err());
        assert!(ScanOptions::parse(r#"{"roots": 42}"#).is_err());
    }

    #[test]
    fn stats_field_names_match_go_tags() {
        // Go: json:"docs_found" / docs_indexed / duration_ms / first_build / finished_at
        let s = Stats {
            files: 1,
            indexed: 2,
            docs_found: 3,
            docs_indexed: 4,
            duration_ms: 5,
            first_build: true,
            finished_at: "2026-01-01T00:00:00Z".into(),
            ..Default::default()
        };
        let v: serde_json::Value = serde_json::to_value(&s).unwrap();
        for key in [
            "files",
            "indexed",
            "added",
            "updated",
            "removed",
            "docs_found",
            "docs_indexed",
            "external",
            "duration_ms",
            "cancelled",
            "first_build",
            "finished_at",
        ] {
            assert!(v.get(key).is_some(), "缺少契约字段 {key}");
        }
    }

    #[test]
    fn search_response_field_names_match_go_tags() {
        let r = SearchResponse {
            query: "q".into(),
            elapsed_ms: 7,
            total: 1,
            total_matches: 9,
            total_files: 2,
            total_dirs: 3,
            total_content: 4,
            hits: vec![FileHit {
                path: "/p".into(),
                name: "p".into(),
                size: 1,
                mtime: 2,
                matched: "content".into(),
                kind: "file".into(),
                snippet: "snip".into(),
            }],
        };
        let v: serde_json::Value = serde_json::to_value(&r).unwrap();
        for key in [
            "query",
            "elapsed_ms",
            "total",
            "total_matches",
            "total_files",
            "total_dirs",
            "total_content",
            "hits",
        ] {
            assert!(v.get(key).is_some(), "缺少契约字段 {key}");
        }
        let hit = &v["hits"][0];
        for key in [
            "path", "name", "size", "mtime", "matched", "kind", "snippet",
        ] {
            assert!(hit.get(key).is_some(), "FileHit 缺少契约字段 {key}");
        }
    }

    #[test]
    fn rfc3339_now_is_parseable() {
        let s = rfc3339_now();
        time::OffsetDateTime::parse(&s, &time::format_description::well_known::Rfc3339)
            .expect("rfc3339_now 应产出合法 RFC3339");
    }

    #[test]
    fn external_feed_defaults() {
        let f: ExternalFeed = serde_json::from_str(r#"{"root":"/x"}"#).unwrap();
        assert_eq!(f.root, "/x");
        assert!(f.dirs.is_empty() && f.files.is_empty());
    }

    #[test]
    fn ingest_entry_content_optional() {
        let e: IngestEntry = serde_json::from_str(
            r#"{"path":"/a.txt","name":"a.txt","size":1,"mtime":2,"kind":"file"}"#,
        )
        .unwrap();
        assert_eq!(e.content, "");
    }
}
