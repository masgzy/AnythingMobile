package core

import (
        "errors"
        "sort"
        "strings"
        "sync"
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
func (c *ContentStore) Add(path, text string) error {
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
        ql := strings.ToLower(strings.TrimSpace(q))
        if ql == "" || limit <= 0 {
                return nil
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
                hits = append(hits, FileHit{
                        Path:    p,
                        Name:    baseName(p),
                        Snippet: snippet(orig, idx, len(ql)),
                        Matched: "content",
                })
                if len(hits) >= limit {
                        break
                }
        }
        return hits
}

// snippet 生成命中位置前后的摘要（前后各取 40 字符）。
func snippet(text string, idx, qLen int) string {
        const ctx = 40
        start := idx - ctx
        if start < 0 {
                start = 0
        }
        end := idx + qLen + ctx
        if end > len(text) {
                end = len(text)
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
