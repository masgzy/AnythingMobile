//! JSON 契约回归测试 —— 与 Go 版输出逐字段对照。
//!
//! Go 侧的对照样例取自 `core/` 的测试断言与结构体 tag；
//! 本文件锁定"字段名一个都不能变"的跨语言契约。

use anything::types::{FileHit, IngestEntry, SearchResponse, Stats};
use anything::{ABI_VERSION, engine_version};

/// Go: json.Marshal(core.Stats{...}) 的字段顺序无关，但键名必须全同。
#[test]
fn stats_json_parses_back_from_go_style_object() {
    // 模拟旧版宿主/测试里出现过的 Go 序列化输出（含全部键）
    let go_style = r#"{
        "files": 120,
        "indexed": 100,
        "added": 30,
        "updated": 5,
        "removed": 2,
        "docs_found": 11,
        "docs_indexed": 9,
        "external": 7,
        "duration_ms": 1234,
        "cancelled": false,
        "first_build": true,
        "finished_at": "2026-10-17T12:00:00Z"
    }"#;
    let s: Stats = serde_json::from_str(go_style).unwrap();
    assert_eq!(s.files, 120);
    assert_eq!(s.docs_found, 11);
    assert_eq!(s.external, 7);
    assert!(s.first_build);

    // 再序列化：键集合与输入完全一致（无新增/缺漏）
    let round: serde_json::Value =
        serde_json::from_str(&serde_json::to_string(&s).unwrap()).unwrap();
    let input: serde_json::Value = serde_json::from_str(go_style).unwrap();
    assert_eq!(
        round.as_object().unwrap().keys().collect::<Vec<_>>(),
        input.as_object().unwrap().keys().collect::<Vec<_>>()
    );
}

#[test]
fn search_response_matches_go_shape() {
    // Go: SearchResponse{Query:"a", Elapsed:1, Total:1, TotalMatches:2,
    //     TotalFiles:1, TotalDirs:0, TotalContent:1, Hits:[...]}
    let resp = SearchResponse {
        query: "a".into(),
        elapsed_ms: 1,
        total: 1,
        total_matches: 2,
        total_files: 1,
        total_dirs: 0,
        total_content: 1,
        hits: vec![FileHit {
            path: "/sdcard/a.txt".into(),
            name: "a.txt".into(),
            size: 3,
            mtime: 1700000000000,
            matched: "content".into(),
            kind: "file".into(),
            snippet: "…a…".into(),
        }],
    };
    let j = serde_json::to_string(&resp).unwrap();
    assert!(j.contains(r#""elapsed_ms":1"#));
    assert!(j.contains(r#""total_matches":2"#));
    assert!(j.contains(r#""total_files":1"#));
    assert!(j.contains(r#""total_dirs":0"#));
    assert!(j.contains(r#""total_content":1"#));
    assert!(j.contains(r#""mtime":1700000000000"#));
    assert!(j.contains(r#""matched":"content""#));
}

#[test]
fn ingest_entry_matches_go_shape() {
    let go_style = r#"{
        "path":"content://x","name":"报告.docx","size":9,
        "mtime":1700000000000,"kind":"file","content":"正文"
    }"#;
    let e: IngestEntry = serde_json::from_str(go_style).unwrap();
    assert_eq!(e.name, "报告.docx");
    assert_eq!(e.content, "正文");
    // content 缺省兼容
    let e2: IngestEntry =
        serde_json::from_str(r#"{"path":"/a","name":"a","size":0,"mtime":0,"kind":"folder"}"#)
            .unwrap();
    assert_eq!(e2.content, "");
    assert_eq!(e2.kind, "folder");
}

#[test]
fn abi_version_stable() {
    // 阶段①契约 v1；任何破坏性变更必须 bump 并同步 Kotlin
    assert_eq!(ABI_VERSION, 1);
    assert!(engine_version().starts_with("0.1.0-alpha."));
    assert!(engine_version().contains("abi=1"));
}
