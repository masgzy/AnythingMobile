//! M4 搜索语法：type: / dir: 过滤器 —— 移植 Go `core/query.go`。
//!
//! 语法形式：
//! ```text
//! type:video 音乐          —— 视频类文件名或正文含"音乐"
//! dir:Download 合同        —— 路径位于名称含 "download" 的目录下
//! dir:"My Documents" 报告  —— 引号包裹含空格的目录名
//! type:doc 预算 dir:工作   —— 过滤器可组合（多 type 取 OR，多 dir 取 AND）
//! ```
//!
//! 语义要点：
//! - type: 对目录命中（kind=folder）不生效 —— 过滤后目录命中一律剔除；
//! - dir: 匹配"父目录路径"的包含关系（大小写不敏感），目录条目
//!   用其自身路径的父目录参与匹配；
//! - 过滤后剩余关键词为空但存在过滤器时，视为非法组合（上层报错）。

use crate::types::FileHit;

/// 查询解析结果（Go `queryFilter` 同款）。
#[derive(Debug, Clone, Default, PartialEq)]
pub struct QueryFilter {
    /// 剩余普通词（保序）。
    pub terms: Vec<String>,
    /// type: 过滤（已归一化为英文标识，保序去重）。
    pub types: Vec<String>,
    /// dir: 过滤（保留原文大小写，匹配时不敏感）。
    pub dirs: Vec<String>,
}

impl QueryFilter {
    /// 剩余关键词重新拼为查询串（空格连接，Go `joinedTerms` 同款）。
    pub fn joined_terms(&self) -> String {
        self.terms.join(" ")
    }
}

/// 过滤词归一化（中英文别名 → 内部标识，Go `typeAliases` 逐项对齐）。
fn type_alias(v: &str) -> Option<&'static str> {
    match v {
        "video" | "视频" | "影片" | "movie" | "movies" => Some("video"),
        "music" | "音乐" | "audio" | "音频" => Some("music"),
        "image" | "图片" | "photo" | "photos" | "照片" | "pic" => Some("image"),
        "doc" | "docs" | "document" | "documents" | "文档" => Some("doc"),
        "file" | "files" | "文件" => Some("file"),
        "apk" | "安装包" => Some("apk"),
        _ => None,
    }
}

/// 扩展名 → 类型标识（Go `extTypeMap` 逐项对齐）。
pub fn ext_type(ext: &str) -> Option<&'static str> {
    match ext {
        ".mp4" | ".avi" | ".mkv" | ".mov" | ".wmv" | ".flv" | ".webm" | ".m4v" | ".3gp"
        | ".3g2" | ".mpg" | ".mpeg" | ".ts" | ".vob" | ".ogv" | ".rm" | ".rmvb" => Some("video"),
        ".mp3" | ".flac" | ".wav" | ".aac" | ".ogg" | ".oga" | ".m4a" | ".wma" | ".opus"
        | ".ape" | ".amr" | ".mid" | ".midi" => Some("music"),
        ".jpg" | ".jpeg" | ".png" | ".gif" | ".webp" | ".bmp" | ".heic" | ".heif" | ".tiff"
        | ".tif" | ".svg" | ".ico" | ".dng" | ".raw" | ".cr2" | ".nef" | ".arw" => Some("image"),
        ".docx" | ".doc" | ".pptx" | ".ppt" | ".xlsx" | ".xls" | ".wps" | ".pdf" | ".txt"
        | ".md" | ".rtf" | ".csv" | ".epub" | ".mobi" | ".azw3" | ".djvu" => Some("doc"),
        ".apk" | ".apkm" | ".xapk" => Some("apk"),
        _ => None,
    }
}

/// 拆出 type:/dir: 过滤器与剩余关键词（Go `parseQuery` 逐语义对齐）。
pub fn parse_query(raw: &str) -> QueryFilter {
    let mut f = QueryFilter::default();
    let mut seen_type: Vec<&str> = Vec::new();
    let rs: Vec<char> = raw.chars().collect();
    let mut i = 0usize;

    while i < rs.len() {
        while i < rs.len() && is_space_rune(rs[i]) {
            i += 1;
        }
        if i >= rs.len() {
            break;
        }
        let rest: String = rs[i..].iter().collect();
        let lower = crate::textutil::lower_simple(&rest);

        if lower.starts_with("type:") {
            i += "type:".len();
            let (val, next) = read_filter_value(&rs[i..]);
            i += next;
            if let Some(t) = type_alias(&crate::textutil::lower_simple(&val))
                && !seen_type.contains(&t)
            {
                seen_type.push(t);
                f.types.push(t.to_string());
            }
        } else if lower.starts_with("dir:") {
            i += "dir:".len();
            let (val, next) = read_filter_value(&rs[i..]);
            i += next;
            if !val.is_empty() {
                f.dirs.push(val);
            }
        } else if rs[i] == '"' {
            i += 1;
            let start = i;
            while i < rs.len() && rs[i] != '"' {
                i += 1;
            }
            f.terms.push(rs[start..i].iter().collect());
            if i < rs.len() {
                i += 1; // 消耗收尾引号
            }
        } else {
            let start = i;
            while i < rs.len() && !is_space_rune(rs[i]) {
                i += 1;
            }
            f.terms.push(rs[start..i].iter().collect());
        }
    }
    f
}

/// 读取过滤器的值：支持 "带空格的引号值" 或连续非空白串。
/// 返回值与消费的 char 数（Go `readFilterValue` 同款）。
fn read_filter_value(s: &[char]) -> (String, usize) {
    if s.is_empty() {
        return (String::new(), 0);
    }
    if s[0] == '"' {
        for j in 1..s.len() {
            if s[j] == '"' {
                return (s[1..j].iter().collect(), j + 1);
            }
        }
        // 未闭合引号：取剩余全部
        return (s[1..].iter().collect(), s.len());
    }
    let mut j = 0;
    while j < s.len() && !is_space_rune(s[j]) {
        j += 1;
    }
    (s[..j].iter().collect(), j)
}

/// 对一组命中应用 type/dir 过滤（Go `filterHits` 同款）。
pub fn filter_hits(hits: Vec<FileHit>, f: &QueryFilter) -> Vec<FileHit> {
    if f.types.is_empty() && f.dirs.is_empty() {
        return hits;
    }
    hits.into_iter()
        .filter(|h| match_types(h, &f.types) && match_dirs(&h.path, &f.dirs))
        .collect()
}

/// 类型过滤：目录条目不匹配任何 type（Go `matchTypes` 同款）。
fn match_types(h: &FileHit, types: &[String]) -> bool {
    if types.is_empty() {
        return true;
    }
    if h.kind == "folder" {
        return false;
    }
    let ext = crate::textutil::ext_of(&h.path);
    let t = ext_type(&ext).unwrap_or("file"); // 未知扩展名归入通用文件
    types.iter().any(|want| want == "file" || want == t)
}

/// 目录过滤：路径的父目录需包含全部 dir 串（AND，大小写不敏感）。
/// 目录条目以自身路径的父目录参与匹配（Go `matchDirs` 同款）。
fn match_dirs(path: &str, dirs: &[String]) -> bool {
    if dirs.is_empty() {
        return true;
    }
    let parent = match path.rfind('/') {
        Some(0) => "/",
        Some(i) => &path[..i],
        None => ".", // 无分隔符：filepath.Dir 返回 "."
    };
    let parent_lower = crate::textutil::lower_simple(parent);
    dirs.iter()
        .all(|d| parent_lower.contains(&crate::textutil::lower_simple(d)))
}

/// 空白判定（Go `isSpaceRune` 同款字符集）。
pub fn is_space_rune(c: char) -> bool {
    matches!(
        c,
        ' ' | '\t' | '\n' | '\r' | '\u{0B}' | '\u{0C}' | '\u{85}' | '\u{A0}'
    )
}

// ---------------------------------------------------------------- 测试

#[cfg(test)]
mod tests {
    use super::*;

    fn hit(path: &str, kind: &str) -> FileHit {
        FileHit {
            path: path.into(),
            name: crate::textutil::base_name(path).into(),
            size: 1,
            mtime: 2,
            matched: "name".into(),
            kind: kind.into(),
            snippet: String::new(),
        }
    }

    #[test]
    fn parse_plain_terms() {
        let f = parse_query("hello world");
        assert_eq!(f.terms, vec!["hello", "world"]);
        assert!(f.types.is_empty() && f.dirs.is_empty());
        assert_eq!(f.joined_terms(), "hello world");
    }

    #[test]
    fn parse_type_filter_with_alias_and_dedup() {
        let f = parse_query("type:视频 音乐 type:movie 报告");
        assert_eq!(f.types, vec!["video"]); // 同义别名去重
        assert_eq!(f.terms, vec!["音乐", "报告"]);
    }

    #[test]
    fn parse_type_unknown_alias_ignored() {
        let f = parse_query("type:whatever 词");
        assert!(f.types.is_empty());
        assert_eq!(f.terms, vec!["词"]);
    }

    #[test]
    fn parse_dir_quoted_value() {
        let f = parse_query(r#"dir:"My Documents" 报告"#);
        assert_eq!(f.dirs, vec!["My Documents"]);
        assert_eq!(f.terms, vec!["报告"]);
    }

    #[test]
    fn parse_dir_value_is_nonspace_run() {
        // Go readFilterValue 同语义：无引号值 = 连续非空白段；
        // 含空格的目录名必须用引号（见 parse_dir_quoted_value）
        let f = parse_query(r#"dir:My Documents"#);
        assert_eq!(f.dirs, vec!["My"]);
        assert_eq!(f.terms, vec!["Documents"]);
    }

    #[test]
    fn parse_only_filters_no_terms() {
        let f = parse_query("type:video");
        assert!(f.terms.is_empty());
        assert_eq!(f.types, vec!["video"]);
    }

    #[test]
    fn filter_type_removes_folders_and_unrelated() {
        let hits = vec![
            hit("/a/movie.mp4", "file"),
            hit("/a/song.mp3", "file"),
            hit("/a/folder", "folder"),
            hit("/a/unknown.xyz", "file"),
        ];
        let f = parse_query("type:video 词");
        let out = filter_hits(hits, &f);
        // 目录与未知扩展名被剔除（unknown.xyz → "file" ≠ video）
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].path, "/a/movie.mp4");
    }

    #[test]
    fn filter_file_type_matches_everything_file() {
        let hits = vec![hit("/a/x.txt", "file"), hit("/a/f", "folder")];
        let f = parse_query("type:file 词");
        let out = filter_hits(hits, &f);
        assert_eq!(out.len(), 1); // folder 不匹配任何 type
    }

    #[test]
    fn filter_dirs_and_semantics_case_insensitive() {
        let hits = vec![
            hit("/storage/emulated/0/Download/a.txt", "file"),
            hit("/storage/emulated/0/DCIM/b.jpg", "file"),
        ];
        let f = parse_query("dir:download 词");
        let out = filter_hits(hits.clone(), &f);
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].path, "/storage/emulated/0/Download/a.txt");

        // AND 语义：两个 dir 都需命中
        let f2 = parse_query("dir:download dir:dcim 词");
        assert!(filter_hits(hits, &f2).is_empty());
    }

    #[test]
    fn dir_filter_uses_parent_for_folders() {
        // 目录条目以自身路径的父目录参与匹配
        let hits = vec![hit("/a/Download/sub", "folder")];
        let f = parse_query("dir:download 词");
        let out = filter_hits(hits, &f);
        assert_eq!(out.len(), 1);
    }

    #[test]
    fn no_filters_passthrough() {
        let hits = vec![hit("/a/x.txt", "file")];
        let f = parse_query("plain");
        assert_eq!(filter_hits(hits.clone(), &f), hits);
    }
}
