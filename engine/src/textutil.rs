//! 文本清洗与归一化 —— 移植 Go `core/textutil.go` + fts 侧工具。
//!
//! 全部语义 1:1 对齐 Go 版：索引与查询两端使用同一套归一化，
//! 是"子串匹配"语义成立的基石。

/// 清洗文档抽取出的正文，保证入库文本可安全渲染与搜索：
/// - 无效 UTF-8 字节直接丢弃（Rust 的 `&str` 天然合法，本函数主要处理
///   控制字符/PUA/非字符；输入侧由 docparse 保证无 U+FFFD 注入）；
/// - C0 控制字符中 \v \f 归一为换行（Word 系软换行/分页的常见来源），
///   其余（\x00-\x08、\x0E-\x1F）与 DEL、C1 控制符一并丢弃；
/// - 私有区 PUA（U+E000-U+F8FF，Wingdings/Symbol 等符号字体映射）丢弃；
/// - 非字符（U+FDD0-U+FDEF 与各平面 U+xFFFE/U+xFFFF）丢弃；
/// - U+2028/U+2029 行/段分隔符归一为 \n；替换字符 U+FFFD 丢弃。
///
/// \t \n \r 与全部合法可打印字符（含 CJK/emoji）原样保留。
pub fn sanitize_text(text: &str) -> String {
    if !needs_sanitize(text) {
        return text.to_string();
    }
    let mut out = String::with_capacity(text.len());
    for r in text.chars() {
        match r {
            '\u{0B}' | '\u{0C}' | '\u{2028}' | '\u{2029}' => out.push('\n'),
            // \t \n \r 合法保留
            '\t' | '\n' | '\r' => out.push(r),
            c if c.is_control() => {} // 其余 C0/DEL/C1 一律丢弃
            '\u{FFFD}' => {}
            c if ('\u{E000}'..='\u{F8FF}').contains(&c) => {} // PUA
            c if ('\u{FDD0}'..='\u{FDEF}').contains(&c) => {} // 非字符区
            c if (c as u32) & 0xFFFE == 0xFFFE => {}          // 各平面 xFFFE/xFFFF
            c => out.push(c),
        }
    }
    out
}

/// 快速扫描：文本是否含任何需要清洗的成分。
/// 判定必须与 [`sanitize_text`] 的改写语义完全一致。
fn needs_sanitize(text: &str) -> bool {
    text.chars().any(|c| match c {
        '\u{0B}' | '\u{0C}' | '\u{2028}' | '\u{2029}' => true,
        '\t' | '\n' | '\r' => false,
        c if c.is_ascii() => c.is_control() || c == '\u{7F}',
        '\u{FFFD}' => true,
        c if ('\u{80}'..='\u{9F}').contains(&c) => true,
        c if ('\u{E000}'..='\u{F8FF}').contains(&c) => true,
        c if ('\u{FDD0}'..='\u{FDEF}').contains(&c) => true,
        c if (c as u32) & 0xFFFE == 0xFFFE => true,
        _ => false,
    })
}

/// 入库前的统一清洗入口：归一各类换行变体后再做字符清洗。
/// 返回的文本保证不含除 \t \n 外的任何控制字符，且是合法 UTF-8。
pub fn normalize_for_store(text: &str) -> String {
    let t = if text.contains('\r') {
        text.replace("\r\n", "\n").replace('\r', "\n")
    } else {
        text.to_string()
    };
    sanitize_text(&t)
}

/// 按 rune 边界截断文本（多字节字符不产生半个字符）。
/// Go `truncateRunes` 同款：从 max_bytes 向前回退到 UTF-8 起始字节。
pub fn truncate_runes(text: &str, max_bytes: usize) -> &str {
    if text.len() <= max_bytes {
        return text;
    }
    let mut cut = max_bytes;
    while cut > 0 && !text.is_char_boundary(cut) {
        cut -= 1;
    }
    &text[..cut]
}

/// Go `strings.ToLower` 同款的**单字符简单小写映射**（Unicode simple mapping）。
///
/// 与 Rust `str::to_lowercase()` 的差异点：Rust 采用完整大小写转换
/// （上下文相关、可能一变多），如 'İ'(U+0130) → "i̇"（2 字符）；
/// Go/本函数为简单映射 İ → "i"（1 字符）。索引与查询两端统一走本函数，
/// 保证 bigram 对齐 —— 与 Go 版索引产物逐字节兼容。
pub fn lower_simple(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        // char::to_lowercase 的首元素即简单映射（其余元素是特殊大小写展开）
        if let Some(first) = c.to_lowercase().next() {
            out.push(first);
        } else {
            out.push(c);
        }
    }
    out
}

/// 取路径最后一段（Go `baseName` 同款：按 '/' 切）。
pub fn base_name(p: &str) -> &str {
    match p.rfind('/') {
        Some(i) => &p[i + 1..],
        None => p,
    }
}

/// 返回小写扩展名（含点，Go `extOf` 同款）。
pub fn ext_of(path: &str) -> String {
    match path.rfind('.') {
        Some(i) => {
            let ext = &path[i..];
            // Go filepath.Ext 取最后一个 '.' 之后（含点），仅当 '.' 在最后一段
            match path.rfind('/') {
                Some(slash) if slash > i => String::new(),
                _ => lower_simple(ext),
            }
        }
        None => String::new(),
    }
}

/// 生成 UTF-8 字符级 2-gram（Go `bigrams` 同款）；单字符返回其自身。
pub fn bigrams(s: &str) -> Vec<String> {
    let chars: Vec<char> = s.chars().collect();
    match chars.len() {
        0 => Vec::new(),
        1 => vec![s.to_string()],
        n => (0..n - 1)
            .map(|i| chars[i..i + 2].iter().collect())
            .collect(),
    }
}

// ---------------------------------------------------------------- 测试

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanitize_keeps_printable_and_newlines() {
        assert_eq!(sanitize_text("hello 世界\ttext\n"), "hello 世界\ttext\n");
        // \v \f → \n
        assert_eq!(sanitize_text("a\u{0B}b\u{0C}c"), "a\nb\nc");
        // C0 控制、DEL、C1 丢弃
        assert_eq!(sanitize_text("a\u{01}b\u{7F}c\u{85}d"), "abcd");
        // PUA / 非字符 / 替换字符 / 行段分隔符
        assert_eq!(sanitize_text("a\u{E000}b\u{FDD0}c\u{10FFFE}d"), "abcd");
        assert_eq!(sanitize_text("a\u{FFFD}b\u{2028}c\u{2029}d"), "ab\nc\nd");
        // emoji 与合法 CJK 原样保留
        assert_eq!(sanitize_text("🎉🎉中文"), "🎉🎉中文");
    }

    #[test]
    fn normalize_for_store_line_endings() {
        assert_eq!(normalize_for_store("a\r\nb\rc\nd"), "a\nb\nc\nd");
        assert_eq!(normalize_for_store("x\u{0B}y"), "x\ny");
    }

    #[test]
    fn truncate_on_char_boundary() {
        let s = "中文abc";
        // 1 字节处是 '中' 的中间 —— 回退到 0
        assert_eq!(truncate_runes(s, 1), "");
        assert_eq!(truncate_runes(s, 3), "中");
        assert_eq!(truncate_runes(s, 4), "中");
        assert_eq!(truncate_runes(s, 100), s);
    }

    #[test]
    fn lower_simple_matches_go_not_rust_full() {
        // Rust to_lowercase 会把 İ 变成 2 字符 "i̇"；Go/本函数为 "i"
        assert_eq!(lower_simple("İ"), "i");
        assert_eq!(lower_simple("ABC xyz 123"), "abc xyz 123");
        assert_eq!(lower_simple("中文ＡＢＣ"), "中文ａｂｃ"); // 全角字母
        assert_eq!(lower_simple("ΑΒΓ"), "αβγ"); // 希腊字母
    }

    #[test]
    fn bigrams_char_level() {
        assert_eq!(bigrams(""), Vec::<String>::new());
        assert_eq!(bigrams("a"), vec!["a"]);
        assert_eq!(bigrams("ab"), vec!["ab"]);
        assert_eq!(bigrams("abc"), vec!["ab", "bc"]);
        assert_eq!(bigrams("中文"), vec!["中文"]);
        assert_eq!(bigrams("中文本"), vec!["中文", "文本"]);
    }

    #[test]
    fn ext_of_lowercase_and_dirs() {
        assert_eq!(ext_of("/a/B.TXT"), ".txt");
        assert_eq!(ext_of("/a.b/c"), "");
        assert_eq!(ext_of("noext"), "");
        assert_eq!(ext_of("archive.tar.gz"), ".gz");
    }

    #[test]
    fn base_name_slash_only() {
        assert_eq!(base_name("/a/b/c.txt"), "c.txt");
        assert_eq!(base_name("c.txt"), "c.txt");
        assert_eq!(base_name("/a/b/"), "");
    }
}
