package core

// FTS 自定义分析器：供 bleve 索引与查询两端共用。
//
// big 字段用 cjk_bigram：字符级 unigram+bigram 切分。
//   - unigram 让单字查询退化为一次 term 查询（与 NameIndex.bigrams 语义对齐）；
//   - bigram 承担候选集收敛（各 bigram 倒排求交），邻接校验由查询层用
//     连续 Position 完成 —— bigram 的 Position 按 Rune 序号 1-based 连续
//     递增无空洞，「查询词的全部 bigram 出现在连续位置」⟺ 查询词是
//     文档的精确子串。
//
// words 字段用 cjk_gse：gse 中文分词（词典 core/data/dict_zh_min.txt）。
//   - 提供词边界视角：查询词命中 gse 完整词条的文档获得额外相关性
//     权重，"合同"整词命中的文档排在"合…同"跨字命中的前面；
//   - 该字段只参与词频统计（无短语/位置查询），token 的 Start/End
//     仅作近似记录；
//   - ASCII 连续段（字母/数字）不经 gse，自行按字母数字 runs 切分，
//     避免 gse 对无空格英文的切分不确定性；其余字符（CJK/全角）段
//     交给 gse 切词。

import (
	"strings"
	"unicode/utf8"

	"github.com/blevesearch/bleve/v2/analysis"
	"github.com/blevesearch/bleve/v2/registry"
	"github.com/go-ego/gse"
)

const (
	// 注册进 bleve registry 的分词器名字（mapping JSON 依名字引用）。
	bigramTokenizerName = "cjk_bigram"
	gseTokenizerName    = "cjk_gse"
)

// registerFTSAnalyzers 把两个自定义分词器注册进 bleve 全局 registry。
// 必须在 bleve.Open 解析 mapping 之前完成（包 init 时机保证）。
func registerFTSAnalyzers() {
	registry.RegisterTokenizer(bigramTokenizerName, func(_ map[string]interface{}, _ *registry.Cache) (analysis.Tokenizer, error) {
		return &bigramTokenizer{}, nil
	})
	registry.RegisterTokenizer(gseTokenizerName, func(_ map[string]interface{}, _ *registry.Cache) (analysis.Tokenizer, error) {
		return &gseTokenizer{seg: sharedSegmenter()}, nil
	})
}

// ---- big 字段：unigram + bigram ----

type bigramTokenizer struct{}

func (t *bigramTokenizer) Tokenize(input []byte) analysis.TokenStream {
	lower := strings.ToLower(string(input))
	if lower == "" {
		return nil
	}
	return runeGramTokens(lower)
}

// runeGramTokens 生成字符级 unigram+bigram token 流。
// bigram 的 Position = 起始 rune 的 1-based 序号且随文本连续递增；
// unigram 的 Position 仅用于频次，不复用同一序列（两 term 的位置
// 序列在倒排里互相独立，互不干扰）。
func runeGramTokens(lower string) analysis.TokenStream {
	rs := []rune(lower)
	n := len(rs)
	rv := make(analysis.TokenStream, 0, n+n/2+1)

	// 预计算每个 rune 的字节起点，避免逐 token 反复切片统计长度。
	starts := make([]int, n+1)
	off := 0
	for i, r := range rs {
		starts[i] = off
		off += utf8.RuneLen(r)
	}
	starts[n] = off

	for i := 0; i < n; i++ {
		// unigram
		rv = append(rv, &analysis.Token{
			Start: starts[i], End: starts[i+1],
			Term:     []byte(string(rs[i : i+1])),
			Position: i + 1, Type: analysis.AlphaNumeric,
		})
		// bigram（Position 必须 = 起始 rune 序号+1，连续无空洞）
		if i+2 <= n {
			rv = append(rv, &analysis.Token{
				Start: starts[i], End: starts[i+2],
				Term:     []byte(string(rs[i : i+2])),
				Position: i + 1, Type: analysis.AlphaNumeric,
			})
		}
	}
	return rv
}

// ---- words 字段：gse 分词 + ASCII 自切 ----

type gseTokenizer struct{ seg *gse.Segmenter }

func (t *gseTokenizer) Tokenize(input []byte) analysis.TokenStream {
	if t == nil || t.seg == nil {
		return nil
	}
	lower := strings.ToLower(string(input))
	if lower == "" {
		return nil
	}

	rs := []rune(lower)
	rv := make(analysis.TokenStream, 0, len(rs)/2+8)
	pos := 0
	off := 0 // 当前 rune 在 lower 中的字节偏移

	appendTok := func(term string, start int) {
		pos++
		rv = append(rv, &analysis.Token{
			Start: start, End: start + len(term),
			Term: []byte(term), Position: pos, Type: analysis.AlphaNumeric,
		})
	}

	i := 0
	for i < len(rs) {
		if rs[i] < utf8.RuneSelf {
			// ASCII 段：按字母数字 runs 自切
			runStart := i
			for i < len(rs) && rs[i] < utf8.RuneSelf {
				i++
			}
			seg := string(rs[runStart:i])
			var cur strings.Builder
			curStart := 0
			scan := 0
			for _, r := range seg {
				w := utf8.RuneLen(r)
				if isASCIILetterDigit(r) {
					if cur.Len() == 0 {
						curStart = scan
					}
					cur.WriteRune(r)
				} else if cur.Len() > 0 {
					appendTok(cur.String(), off+curStart)
					cur.Reset()
				}
				scan += w
			}
			if cur.Len() > 0 {
				appendTok(cur.String(), off+curStart)
			}
			off += len(seg)
			continue
		}
		// 非 ASCII（CJK 等）段：整段交给 gse 切词
		runStart := i
		for i < len(rs) && rs[i] >= utf8.RuneSelf {
			i++
		}
		seg := string(rs[runStart:i])
		segOff := off
		for _, w := range t.seg.Cut(seg, false) {
			w = strings.TrimSpace(w)
			if w == "" {
				continue
			}
			appendTok(w, segOff)
			segOff += len(w)
		}
		off += len(seg)
	}
	return rv
}

func isASCIILetterDigit(r rune) bool {
	return ('a' <= r && r <= 'z') || ('0' <= r && r <= '9')
}
