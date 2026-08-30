package core

import (
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"unicode/utf8"
)

// NameIndex 基于 2-gram 倒排的文件名索引：
//   - 查询词 >= 2 字符：先取各 bigram 倒排表求交集，再对候选做子串验证；
//   - 单字符查询：退化为全量线性扫描（文件名基数通常在万级，可接受）。
//
// 后续里程碑可平滑升级为持久化 n-gram（M3）。
type NameIndex struct {
	mu    sync.RWMutex
	docs  map[uint32]*docInfo
	grams map[string]map[uint32]struct{}
	next  uint32
}

type docInfo struct {
	path  string
	name  string
	lower string
	size  int64
	mtime int64
}

// NewNameIndex 创建空的文件名索引。
func NewNameIndex() *NameIndex {
	return &NameIndex{
		docs:  make(map[uint32]*docInfo),
		grams: make(map[string]map[uint32]struct{}),
	}
}

// Add 将一个文件加入索引；同名路径重复加入会先移除旧记录（支持增量重扫）。
func (n *NameIndex) Add(path string, size, mtime int64) {
	base := filepath.Base(path)
	lower := strings.ToLower(base)
	if lower == "" {
		return
	}

	n.mu.Lock()
	defer n.mu.Unlock()

	// 去重：同一路径先删旧 gram 记录
	if old, ok := n.findByPathLocked(path); ok {
		n.removeLocked(old)
	}

	id := n.next
	n.next++
	doc := &docInfo{path: path, name: base, lower: lower, size: size, mtime: mtime}
	n.docs[id] = doc
	for _, g := range bigrams(lower) {
		set := n.grams[g]
		if set == nil {
			set = make(map[uint32]struct{})
			n.grams[g] = set
		}
		set[id] = struct{}{}
	}
}

// Count 返回已索引文件数。
func (n *NameIndex) Count() int64 {
	n.mu.RLock()
	defer n.mu.RUnlock()
	return int64(len(n.docs))
}

// Search 按子串查询文件名，结果按"名字更短优先、修改时间更新优先"排序。
func (n *NameIndex) Search(q string, limit int) []FileHit {
	ql := strings.ToLower(strings.TrimSpace(q))
	if ql == "" || limit <= 0 {
		return nil
	}
	runes := []rune(ql)

	n.mu.RLock()
	defer n.mu.RUnlock()

	var candidates map[uint32]struct{}
	if utf8.RuneCountInString(ql) >= 2 {
		for i := 0; i+2 <= len(runes); i++ {
			g := string(runes[i : i+2])
			set := n.grams[g]
			if len(set) == 0 {
				return nil // 某个 bigram 无命中 => 不可能子串匹配
			}
			if candidates == nil {
				candidates = make(map[uint32]struct{}, len(set))
				for id := range set {
					candidates[id] = struct{}{}
				}
				continue
			}
			for id := range candidates {
				if _, ok := set[id]; !ok {
					delete(candidates, id)
				}
			}
			if len(candidates) == 0 {
				return nil
			}
		}
	}

	hits := make([]FileHit, 0, 32)
	for id, doc := range n.docs {
		if candidates != nil {
			if _, ok := candidates[id]; !ok {
				continue
			}
		}
		if strings.Contains(doc.lower, ql) {
			hits = append(hits, FileHit{
				Path: doc.path, Name: doc.name,
				Size: doc.size, ModTime: doc.mtime, Matched: "name",
			})
		}
	}

	sort.Slice(hits, func(i, j int) bool {
		if len(hits[i].Name) != len(hits[j].Name) {
			return len(hits[i].Name) < len(hits[j].Name)
		}
		return hits[i].ModTime > hits[j].ModTime
	})
	if len(hits) > limit {
		hits = hits[:limit]
	}
	return hits
}

// ---- 内部工具 ----

func (n *NameIndex) findByPathLocked(path string) (*docInfo, bool) {
	for _, doc := range n.docs {
		if doc.path == path {
			return doc, true
		}
	}
	return nil, false
}

func (n *NameIndex) removeLocked(doc *docInfo) {
	for id, d := range n.docs {
		if d == doc {
			for _, g := range bigrams(doc.lower) {
				if set := n.grams[g]; set != nil {
					delete(set, id)
					if len(set) == 0 {
						delete(n.grams, g)
					}
				}
			}
			delete(n.docs, id)
			return
		}
	}
}

// bigrams 生成 UTF-8 字符级 2-gram；单字符返回其自身，保证单字也能倒排。
func bigrams(s string) []string {
	r := []rune(s)
	if len(r) == 0 {
		return nil
	}
	if len(r) == 1 {
		return []string{s}
	}
	out := make([]string, 0, len(r)-1)
	for i := 0; i+2 <= len(r); i++ {
		out = append(out, string(r[i:i+2]))
	}
	return out
}
