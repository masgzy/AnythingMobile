package core

import (
	"errors"
	"sort"
	"strings"
	"sync"
	"unicode/utf8"
)

// ErrContentFull 内容库容量已满，不再接收新文档。
var ErrContentFull = errors.New("core: 全文内容库已满")

// ContentStore v0 朴素全文内容库：内存 map + 子串匹配。
// M3 里程碑将替换为 bleve + gse 的持久化倒排索引，接口保持不变。
type ContentStore struct {
	mu       sync.RWMutex
	texts    map[string]string // path -> 全文小写
	orig     map[string]string // path -> 全文原文（用于摘要）
	order    []string
	maxBytes int64
	bytes    int64
}

// NewContentStore 创建内容库（默认容量 64MB 文本）。
func NewContentStore() *ContentStore {
	return &ContentStore{
		texts:    make(map[string]string),
		orig:     make(map[string]string),
		maxBytes: 64 << 20,
	}
}

// Add 写入/更新一个文档的全文。
// 入库前统一清洗（normalizeForStore）：剔除无效 UTF-8、控制字符、
// 符号字体 PUA 字符等 UI 无法渲染的成分，从源头避免搜索结果出现
// "菱形问号"。宿主 ExternalParser 与内置 OOXML 解析共用同一入口。
func (c *ContentStore) Add(path, text string) error {
	text = normalizeForStore(text)
	if strings.TrimSpace(text) == "" {
		return errors.New("core: 文档正文为空")
	}
	c.mu.Lock()
	defer c.mu.Unlock()

	if old, ok := c.texts[path]; ok {
		c.bytes -= int64(len(old))
	} else {
		c.order = append(c.order, path)
	}
	if c.bytes+int64(len(text)) > c.maxBytes {
		return ErrContentFull
	}
	c.texts[path] = strings.ToLower(text)
	c.orig[path] = text
	c.bytes += int64(len(text))
	return nil
}

// Remove 移除一个文档；返回是否存在。
func (c *ContentStore) Remove(path string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	old, ok := c.texts[path]
	if !ok {
		return false
	}
	c.bytes -= int64(len(old))
	delete(c.texts, path)
	delete(c.orig, path)
	c.removeFromOrder(path)
	return true
}

// RemoveExcept 仅保留 seen 中的文档，其余移除；返回被移除的路径数。
func (c *ContentStore) RemoveExcept(seen map[string]struct{}) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	removed := 0
	for p, old := range c.texts {
		if _, keep := seen[p]; keep {
			continue
		}
		c.bytes -= int64(len(old))
		delete(c.texts, p)
		delete(c.orig, p)
		c.removeFromOrder(p)
		removed++
	}
	return removed
}

// removeFromOrder 从 order 切片中移除 path。调用方需持有写锁。
func (c *ContentStore) removeFromOrder(path string) {
	for i, p := range c.order {
		if p == path {
			c.order = append(c.order[:i], c.order[i+1:]...)
			return
		}
	}
}

// Count 返回已收录文档数。
func (c *ContentStore) Count() int64 {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return int64(len(c.texts))
}

// Search 朴素子串搜索，命中返回带上下文摘要的结果。
func (c *ContentStore) Search(q string, limit int) []FileHit {
	hits, _ := c.searchAll(q)
	if limit > 0 && len(hits) > limit {
		hits = hits[:limit]
	}
	return hits
}

// searchAll 返回全部命中（路径序稳定，不截断）与真实命中总数。
// 与旧 Search 的差异只是不再提前 break：本来就要对全部文档做
// strings.Index 扫描，省去的只是截断，统计总数无需二次遍历。
func (c *ContentStore) searchAll(q string) ([]FileHit, int) {
	ql := strings.ToLower(strings.TrimSpace(q))
	if ql == "" {
		return nil, 0
	}

	c.mu.RLock()
	defer c.mu.RUnlock()

	paths := make([]string, 0, len(c.order))
	paths = append(paths, c.order...)
	sort.Strings(paths) // 稳定输出

	hits := make([]FileHit, 0, 8)
	for _, p := range paths {
		lower := c.texts[p]
		idx := strings.Index(lower, ql)
		if idx < 0 {
			continue
		}
		orig := c.orig[p]
		src := orig
		if len(lower) != len(orig) {
			// 大小写转换改变字节长度的罕见情形（如土耳其 İ）：
			// lower 的字节偏移映射不到 orig，退化为对小写文本取摘要
			src = lower
		}
		hits = append(hits, FileHit{
			Path:    p,
			Name:    baseName(p),
			Snippet: snippet(src, idx, len(ql)),
			Matched: "content",
		})
	}
	return hits, len(hits)
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

func baseName(p string) string {
	if i := strings.LastIndexByte(p, '/'); i >= 0 {
		return p[i+1:]
	}
	return p
}
