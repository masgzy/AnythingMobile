//! 分词层 —— Go `core/ftsanalyzer.go` 的 Rust 等价物。
//!
//! - [`shared_jieba`]：jieba 分词器单例，词典与 Go 版同源
//!   （`core/data/dict_zh_min.txt` gzip 内嵌，加载后分词口径一致）；
//! - [`CjkBigramTokenizer`]：big 字段用 —— 字符级 unigram+bigram；
//!   bigram 的 Position = 起始 rune 的 1-based 序号且随文本连续递增；
//! - [`JiebaTokenizer`]：words 字段用 —— ASCII 连续段按字母数字 runs
//!   自切（避免 jieba 对无空格英文的切分不确定性），其余（CJK/全角）段
//!   交给 jieba 切词（gse 同款两段式）。
//!
//! 小写化统一走 [`crate::textutil::lower_simple`]（Go strings.ToLower 同款
//! 单字符简单映射），索引与查询两端一致。

use std::io::BufReader;
use std::sync::OnceLock;

use jieba_rs::Jieba;
use tantivy::tokenizer::{BoxTokenStream, Token, TokenStream, Tokenizer};

/// 与 Go 版同源的极简中文词典（gzip 压缩，11 万词条 / 解压 1.5MB）。
static DICT_GZ: &[u8] = include_bytes!("../data/dict_zh_min.txt.gz");

static JIEBA: OnceLock<Jieba> = OnceLock::new();

/// jieba 分词器单例（索引与查询两端共享；词典加载一次性）。
/// 词典加载失败不致命：words 字段自动降级为空（big 字段语义不受影响）。
pub fn shared_jieba() -> Option<&'static Jieba> {
    if let Some(j) = JIEBA.get() {
        return Some(j);
    }
    let mut jieba = Jieba::empty();
    let gz = flate2::read::GzDecoder::new(DICT_GZ);
    let mut reader = BufReader::new(gz);
    if jieba.load_dict(&mut reader).is_err() {
        return None;
    }
    let _ = JIEBA.set(jieba);
    JIEBA.get()
}

// ---------------------------------------------------------------- big 字段

/// 字符级 unigram+bigram 分词器（Go `bigramTokenizer` 直译）。
#[derive(Debug, Clone, Default)]
pub struct CjkBigramTokenizer;

/// 生成字符级 unigram+bigram token 序列。
/// bigram 的 Position = 起始 rune 的 1-based 序号且随文本连续递增；
/// unigram 的 Position 仅用于频次，不复用同一序列（两 term 的位置
/// 序列在倒排里互相独立，互不干扰）。
fn rune_gram_tokens(lower: &str) -> Vec<Token> {
    let chars: Vec<char> = lower.chars().collect();
    let n = chars.len();
    let mut out = Vec::with_capacity(n + n / 2 + 1);

    // 预计算每个 char 的字节起点，避免逐 token 反复统计长度。
    let mut starts = Vec::with_capacity(n + 1);
    let mut off = 0usize;
    for c in &chars {
        starts.push(off);
        off += c.len_utf8();
    }
    starts.push(off);

    for i in 0..n {
        // unigram
        out.push(Token {
            offset_from: starts[i],
            offset_to: starts[i + 1],
            position: i,
            text: chars[i].to_string(),
            position_length: 1,
        });
        // bigram（Position = 起始 rune 序号，连续无空洞）
        if i + 2 <= n {
            out.push(Token {
                offset_from: starts[i],
                offset_to: starts[i + 2],
                position: i,
                text: chars[i..i + 2].iter().collect(),
                position_length: 1,
            });
        }
    }
    out
}

/// 预生成的 token 流（游标消费）。
struct VecTokenStream {
    tokens: Vec<Token>,
    idx: usize,
    cur: Token,
}

impl VecTokenStream {
    fn new(tokens: Vec<Token>) -> Self {
        VecTokenStream {
            tokens,
            idx: 0,
            cur: Token::default(),
        }
    }
}

impl TokenStream for VecTokenStream {
    fn advance(&mut self) -> bool {
        if self.idx < self.tokens.len() {
            self.cur = self.tokens[self.idx].clone();
            self.idx += 1;
            true
        } else {
            false
        }
    }

    fn token(&self) -> &Token {
        &self.cur
    }

    fn token_mut(&mut self) -> &mut Token {
        &mut self.cur
    }
}

impl Tokenizer for CjkBigramTokenizer {
    type TokenStream<'a> = BoxTokenStream<'a>;

    fn token_stream<'a>(&'a mut self, text: &'a str) -> Self::TokenStream<'a> {
        let lower = crate::textutil::lower_simple(text);
        if lower.is_empty() {
            return BoxTokenStream::new(VecTokenStream::new(Vec::new()));
        }
        BoxTokenStream::new(VecTokenStream::new(rune_gram_tokens(&lower)))
    }
}

// ---------------------------------------------------------------- words 字段

/// jieba 分词器（Go `gseTokenizer` 直译）：ASCII 段自切 + CJK 段交 jieba。
#[derive(Debug, Clone, Default)]
pub struct JiebaTokenizer;

impl Tokenizer for JiebaTokenizer {
    type TokenStream<'a> = BoxTokenStream<'a>;

    fn token_stream<'a>(&'a mut self, text: &'a str) -> Self::TokenStream<'a> {
        let lower = crate::textutil::lower_simple(text);
        if lower.is_empty() {
            return BoxTokenStream::new(VecTokenStream::new(Vec::new()));
        }
        let tokens = jieba_tokens(&lower);
        BoxTokenStream::new(VecTokenStream::new(tokens))
    }
}

/// words 字段的 token 序列：ASCII 连续段按字母数字 runs 自切，
/// 其余字符（CJK/全角）段整段交给 jieba 切词（Go `gseTokenizer` 同款）。
/// Position 为 token 序号（0-based 连续递增，无短语查询依赖）。
fn jieba_tokens(lower: &str) -> Vec<Token> {
    let mut out: Vec<Token> = Vec::new();
    let mut pos = 0usize;
    let push = |text: String, offset_from: usize, out: &mut Vec<Token>, pos: &mut usize| {
        out.push(Token {
            offset_from,
            offset_to: offset_from + text.len(),
            position: *pos,
            text,
            position_length: 1,
        });
        *pos += 1;
    };

    let mut seg_start: Option<usize> = None; // 当前 ASCII 段起点（字节）
    let mut cjk_start: Option<usize> = None; // 当前非 ASCII 段起点（字节）

    macro_rules! flush_ascii {
        ($end:expr) => {{
            if let Some(s) = seg_start.take() {
                let seg = &lower[s..$end];
                let mut cur = String::new();
                let mut cur_start = 0usize;
                for (bi, c) in seg.char_indices() {
                    if c.is_ascii_lowercase() || c.is_ascii_digit() {
                        if cur.is_empty() {
                            cur_start = bi;
                        }
                        cur.push(c);
                    } else if !cur.is_empty() {
                        push(std::mem::take(&mut cur), s + cur_start, &mut out, &mut pos);
                    }
                }
                if !cur.is_empty() {
                    push(cur, s + cur_start, &mut out, &mut pos);
                }
            }
        }};
    }
    macro_rules! flush_cjk {
        ($end:expr) => {{
            if let Some(s) = cjk_start.take() {
                let seg = &lower[s..$end];
                if let Some(jieba) = shared_jieba() {
                    for w in jieba.cut(seg, false) {
                        let w = w.word.trim();
                        if w.is_empty() {
                            continue;
                        }
                        push(w.to_string(), s, &mut out, &mut pos);
                    }
                }
            }
        }};
    }

    for (bi, c) in lower.char_indices() {
        if c.is_ascii() {
            flush_cjk!(bi);
            if seg_start.is_none() {
                seg_start = Some(bi);
            }
        } else {
            flush_ascii!(bi);
            if cjk_start.is_none() {
                cjk_start = Some(bi);
            }
        }
    }
    flush_ascii!(lower.len());
    flush_cjk!(lower.len());
    out
}

// ---------------------------------------------------------------- 测试

#[cfg(test)]
mod tests {
    use super::*;

    fn terms_of(mut t: impl Tokenizer, text: &str) -> Vec<String> {
        // Tokenizer::token_stream 以 &mut self 调用，按值传入即可
        let mut stream = t.token_stream(text);
        let mut out = Vec::new();
        stream.process(&mut |tk| out.push(tk.text.clone()));
        out
    }

    #[test]
    fn jieba_dict_loads_from_gz() {
        let j = shared_jieba().expect("词典应成功加载");
        // 词典内的词条必须能整词切出（与 Go gse 同源词典同口径）
        let words: Vec<&str> = j
            .cut("中华人民共和国", false)
            .iter()
            .map(|t| t.word)
            .collect();
        assert!(
            words.contains(&"中华人民共和国") || words.contains(&"中华"),
            "词典切词异常: {words:?}"
        );
    }

    #[test]
    fn bigram_tokenizer_unigram_and_bigram() {
        let t = CjkBigramTokenizer;
        assert_eq!(terms_of(t.clone(), "AB"), vec!["a", "ab", "b"]);
        assert_eq!(terms_of(t.clone(), "中文"), vec!["中", "中文", "文"]);
        // 空串无 token
        assert_eq!(terms_of(t.clone(), ""), Vec::<String>::new());
    }

    #[test]
    fn bigram_positions_continuous() {
        let mut t = CjkBigramTokenizer;
        let mut stream = t.token_stream("abc");
        let mut bigram_positions = Vec::new();
        stream.process(&mut |tk| {
            if tk.text.chars().count() == 2 {
                bigram_positions.push(tk.position);
            }
        });
        assert_eq!(bigram_positions, vec![0, 1], "bigram 位置连续无空洞");
    }

    #[test]
    fn jieba_tokenizer_ascii_runs_self_split() {
        let t = JiebaTokenizer;
        // 纯 ASCII：按字母数字 runs 切，非字母数字字符为分隔
        assert_eq!(
            terms_of(t.clone(), "abc-123.txt"),
            vec!["abc", "123", "txt"]
        );
    }

    #[test]
    fn jieba_tokenizer_cjk_via_dict() {
        let t = JiebaTokenizer;
        let terms = terms_of(t.clone(), "中文文本");
        assert!(!terms.is_empty());
        assert!(
            terms.iter().any(|w| w.chars().count() >= 2),
            "CJK 段应交 jieba 切出词条: {terms:?}"
        );
    }

    #[test]
    fn jieba_tokenizer_mixed() {
        // Go gseTokenizer 同语义：ASCII 段按字母数字 runs 自切，
        // "2024final" 为一个连续 run（字母数字之间无分隔不切断）
        let t = JiebaTokenizer;
        let terms = terms_of(t.clone(), "报告2024final.docx");
        assert!(terms.contains(&"2024final".to_string()), "实际: {terms:?}");
        assert!(terms.contains(&"docx".to_string()), "实际: {terms:?}");
        assert!(
            !terms.contains(&"final".to_string()),
            "run 不中途切断: {terms:?}"
        );
        assert!(
            !terms.contains(&"2024".to_string()),
            "run 不中途切断: {terms:?}"
        );
    }
}
