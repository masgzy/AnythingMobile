package core

// 全文搜索查询层：候选集收敛 → 存储文本精确校验 → 相关性排序。
//
// 语义（对 v0 子串匹配的精确扩展）：
//   - 查询按空白分词，多词之间 AND（各自独立命中即可）；
//   - 单个词内部保持"精确子串"语义：bigram 倒排交集只做候选收敛
//     （子串命中的必要条件），随后对候选读取存储文本做 strings.Index
//     精确校验 —— 不产生近似匹配的假阳性；
//   - 单字查询直接走 unigram 倒排（存在即命中，无需校验）；
//   - 相关性 = Σ(查询 bigram 在文档中的出现次数) + 4×(gse 整词命中
//     次数)，同分按路径字典序稳定输出。

import (
	"context"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"unicode/utf8"

	index "github.com/blevesearch/bleve_index_api"
)

// verifiedHit 校验后的命中（score 用于排序）。
type verifiedHit struct {
	hit   FileHit
	id    string
	score int
}

// searchAll 返回命中（按相关性降序，截断到 verifyCap）与命中总数。
// verifyCap <= 0 表示不设上限（全量验证；兼容旧调用方与测试）。
//
// 性能模型（alpha16，针对高频词查询的验证阶段开销）：
//   - ≤2 rune 词的候选集即精确命中：unigram/bigram 倒排命中 ⟺ 该词是
//     归一化文本的精确子串（bigram 按相邻 rune 切分，索引与查询两端
//     归一化一致），因此 total 恒精确；验证阶段只为取摘要与词边界
//     加分，只对前 verifyCap 个候选读取存储正文；
//   - ≥3 rune 词存在跨 bigram 假阳性（各 bigram 命中但位置不相邻，
//     如 "会议纪要" 命中 "会议…纪要"），需逐文档读正文校验：候选数
//     超预算时按预评分降序验证、凑满即停，total 为已验证数（下界；
//     实际场景长词候选集小，几乎不走这条路径）；候选数不超预算时
//     仍全量验证，total 精确。
func (c *ContentStore) searchAll(q string, verifyCap int) ([]FileHit, int) {
	ql := strings.ToLower(normalizeForStore(strings.TrimSpace(q)))
	if ql == "" || c.broken.Load() {
		return nil, 0
	}
	terms := strings.Fields(ql)
	if len(terms) == 0 {
		return nil, 0
	}

	adv, err := c.idx.Advanced()
	if err != nil {
		return nil, 0
	}
	reader, err := adv.Reader()
	if err != nil {
		return nil, 0
	}
	defer reader.Close()

	// ---- 第一阶段：bigram/unigram 倒排收敛候选集 ----
	// cand: internalID -> Σ(各 bigram 频次)（频次合计兼作粗相关性信号）
	var cand map[string]int
	for _, term := range terms {
		next := c.candidatesForTerm(reader, term)
		if len(next) == 0 {
			return nil, 0 // 任一词无候选 => AND 不可能命中
		}
		if cand == nil {
			cand = next
			continue
		}
		for id, f := range cand {
			if nf, ok := next[id]; ok {
				cand[id] = f + nf
			} else {
				delete(cand, id)
			}
		}
		if len(cand) == 0 {
			return nil, 0
		}
	}

	// ---- 第二阶段：gse 整词命中频次（词边界加权）----
	wordFreq := c.wordFrequencies(reader, ql)

	// ---- 预评分排序：score = bigram 频次 + 4×整词频次。
	// 与验证后的最终评分一致（验证不再改变 score），该序即最终序；
	// 提前终止时按此序消费，保证截断保留的是高分文档。----
	candidateExact := true
	for _, t := range terms {
		if utf8.RuneCountInString(t) > 2 {
			candidateExact = false
			break
		}
	}
	type ordItem struct {
		iid, ext string
		score    int
	}
	order := make([]ordItem, 0, len(cand))
	for iid := range cand {
		ext, err := reader.ExternalID(index.IndexInternalID(iid))
		if err != nil {
			continue
		}
		order = append(order, ordItem{iid: iid, ext: ext,
			score: cand[iid] + wordFreq[ext]*wordBonusScore})
	}
	if len(order) == 0 {
		return nil, 0
	}
	sort.Slice(order, func(i, j int) bool {
		if order[i].score != order[j].score {
			return order[i].score > order[j].score
		}
		return order[i].ext < order[j].ext
	})

	// ---- 验证预算：候选数不超预算 => 全量验证（total 精确）；
	// 超预算 => 只验证预评分最高的前 verifyCap 个 ----
	capped := verifyCap > 0 && verifyCap < len(order)
	if !capped {
		verifyCap = len(order)
	}

	// ---- 第三阶段：存储文本精确校验 + 摘要（按预评分降序消费，
	// 凑满 verifyCap 即提前收工）----
	var (
		mu      sync.Mutex
		wg      sync.WaitGroup
		results []verifiedHit
		stop    atomic.Bool
	)
	const verifyWorkers = 4
	ch := make(chan ordItem, verifyWorkers)
	for w := 0; w < verifyWorkers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for it := range ch {
				text, ok := fetchStoredText(reader, it.ext)
				if !ok {
					continue
				}
				tl := strings.ToLower(text)
				firstOff := -1
				verified := true
				for _, term := range terms {
					off := strings.Index(tl, term)
					if off < 0 {
						verified = false
						break
					}
					if firstOff < 0 {
						firstOff = off
					}
				}
				if !verified {
					continue
				}
				src := text
				if len(tl) != len(text) {
					// 大小写转换改变字节长度（土耳其 İ 类）：
					// 偏移映射不到原文，退化为对小写文本取摘要
					src = tl
				}
				mu.Lock()
				if len(results) < verifyCap { // 通道缓冲可致微量超额，防御性截断
					results = append(results, verifiedHit{
						hit: FileHit{
							Path:    it.ext,
							Name:    baseName(it.ext),
							Matched: "content",
							Kind:    "file",
							Snippet: snippet(src, firstOff, len(terms[0])),
						},
						id:    it.ext,
						score: it.score,
					})
				}
				done := len(results) >= verifyCap
				mu.Unlock()
				if done {
					stop.Store(true)
				}
			}
		}()
	}
	go func() {
		for _, it := range order {
			if stop.Load() {
				break
			}
			ch <- it
		}
		close(ch)
	}()
	wg.Wait()

	total := len(order)
	if !candidateExact && capped {
		// 长词 + 大候选集：只验证了前缀，total 为已验证数（下界）
		total = len(results)
	}

	sort.Slice(results, func(i, j int) bool {
		if results[i].score != results[j].score {
			return results[i].score > results[j].score
		}
		return results[i].id < results[j].id
	})
	hits := make([]FileHit, len(results))
	for i, r := range results {
		hits[i] = r.hit
	}
	return hits, total
}

// candidatesForTerm 单个词的候选集：unigram 直查或 bigram 交集。
func (c *ContentStore) candidatesForTerm(reader index.IndexReader, term string) map[string]int {
	rs := []rune(term)
	if len(rs) == 0 {
		return nil
	}
	if len(rs) == 1 {
		f1, _ := c.collectPostings(reader, term, "big", false)
		return f1
	}
	var out map[string]int
	for i := 0; i+2 <= len(rs); i++ {
		next, _ := c.collectPostings(reader, string(rs[i:i+2]), "big", false)
		if len(next) == 0 {
			return nil
		}
		if out == nil {
			out = next
			continue
		}
		for id, f := range out {
			if nf, ok := next[id]; ok {
				out[id] = f + nf
			} else {
				delete(out, id)
			}
		}
		if len(out) == 0 {
			return nil
		}
	}
	return out
}

// collectPostings 枚举一个 term 的倒排（internalID 为键）。
// withVectors=true 时同时返回各文档的 Position 列表（邻接校验备用）。
func (c *ContentStore) collectPostings(reader index.IndexReader, term, field string, withVectors bool) (map[string]int, map[string][]int) {
	tfr, err := reader.TermFieldReader(context.Background(), []byte(term), field, true, false, withVectors)
	if err != nil {
		return nil, nil
	}
	defer tfr.Close()
	freqs := map[string]int{}
	var positions map[string][]int
	var preAlloc index.TermFieldDoc
	for {
		td, err := tfr.Next(&preAlloc)
		if err != nil || td == nil {
			break
		}
		id := string(td.ID)
		freqs[id] = int(td.Freq)
		if withVectors {
			if positions == nil {
				positions = map[string][]int{}
			}
			for _, v := range td.Vectors {
				positions[id] = append(positions[id], int(v.Pos))
			}
		}
	}
	return freqs, positions
}

// wordFrequencies 查询词经 gse 切词后，各整词在 words 字段的命中频次合计。
func (c *ContentStore) wordFrequencies(reader index.IndexReader, ql string) map[string]int {
	seg := sharedSegmenter()
	var tokens []string
	for _, w := range seg.Cut(ql, false) {
		w = strings.TrimSpace(w)
		if w != "" && len([]rune(w)) >= 2 {
			tokens = append(tokens, w)
		}
	}
	out := map[string]int{}
	for _, tok := range tokens {
		freqs, _ := c.collectPostings(reader, tok, "words", false)
		for id, f := range freqs {
			out[id] += f
		}
	}
	return out
}

// fetchStoredText 读取文档的存储正文（text 字段）。
func fetchStoredText(reader index.IndexReader, externalID string) (string, bool) {
	doc, err := reader.Document(externalID)
	if err != nil || doc == nil {
		return "", false
	}
	var text string
	found := false
	doc.VisitFields(func(f index.Field) {
		if f.Name() == "text" {
			text = string(f.Value())
			found = true
		}
	})
	if !found {
		return "", false
	}
	return text, true
}

// queryTokens 供测试复用：查询词的 gse 切词结果（长度≥2 的词）。
func queryTokens(q string) []string {
	seg := sharedSegmenter()
	var out []string
	for _, w := range seg.Cut(strings.ToLower(normalizeForStore(q)), false) {
		w = strings.TrimSpace(w)
		if len([]rune(w)) >= 2 {
			out = append(out, w)
		}
	}
	return out
}

// snippet 生成命中位置前后的摘要（前后各取约 40 字节）。
// 切片必须对齐 UTF-8 字符边界：start 回退到 rune 起点、end 前扩到
// rune 边界，否则半个多字节序列在 UI 上渲染为 U+FFFD（菱形）。
func snippet(text string, idx, qLen int) string {
	const ctx = 40
	if idx < 0 || idx > len(text) || idx+qLen > len(text) {
		// 偏移异常（不应发生）：退化为开头截取，保证不越界
		idx, qLen = 0, 0
	}
	start := idx - ctx
	if start < 0 {
		start = 0
	} else {
		for start < idx && !utf8.RuneStart(text[start]) {
			start++
		}
	}
	end := idx + qLen + ctx
	if end > len(text) {
		end = len(text)
	} else {
		for end < len(text) && !utf8.RuneStart(text[end]) {
			end++
		}
	}
	s := text[start:end]
	s = strings.ReplaceAll(s, "\n", " ")
	s = strings.TrimSpace(s)
	if start > 0 {
		s = "…" + s
	}
	if end < len(text) {
		s += "…"
	}
	return s
}

// baseName 取路径最后一段（FTS 文档 ID 即完整路径）。
func baseName(p string) string {
	if i := strings.LastIndexByte(p, '/'); i >= 0 {
		return p[i+1:]
	}
	return p
}
