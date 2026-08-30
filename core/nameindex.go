package core

import (
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"unicode/utf8"
)

// NameIndex 基于 2-gram 倒排的名称索引（文件与目录通用，由 kind 区分）：
//   - 查询词 >= 2 字符：先取各 bigram 倒排表求交集，再对候选做子串验证；
//   - 单字符查询：退化为全量线性扫描（名称基数通常在十万级，可接受）。
//
// v0.2 增量扫描改造：
//   - byPath 提供路径到文档的 O(1) 映射（原实现为 O(N) 全表扫描，
//     全量索引十万文件时退化为 O(N^2)，是实测性能瓶颈）；
//   - LookupPath / RemovePath / RemoveExcept 支撑"进入应用自动增量重扫"。
type NameIndex struct {
	mu    sync.RWMutex
	kind  string // "file" | "folder"，写入搜索结果的 Kind 字段
	docs  map[uint32]*docInfo
	byPath map[string]uint32
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

// NewNameIndex 创建空的名称索引；kind 取 "file" 或 "folder"。
func NewNameIndex(kind string) *NameIndex {
	return &NameIndex{
		kind:   kind,
		docs:   make(map[uint32]*docInfo),
		byPath: make(map[string]uint32),
		grams:  make(map[string]map[uint32]struct{}),
	}
}

// Add 将一个名称加入索引；同路径重复加入会先移除旧记录（支持增量重扫）。
func (n *NameIndex) Add(path string, size, mtime int64) {
	base := filepath.Base(path)
	lower := strings.ToLower(base)
	if lower == "" {
		return
	}

	n.mu.Lock()
	defer n.mu.Unlock()

	// 去重：同一路径先删旧 gram 记录（O(1) 定位）
	if id, ok := n.byPath[path]; ok {
		n.removeLocked(id)
	}

	id := n.next
	n.next++
	doc := &docInfo{path: path, name: base, lower: lower, size: size, mtime: mtime}
	n.docs[id] = doc
	n.byPath[path] = id
	for _, g := range bigrams(lower) {
		set := n.grams[g]
		if set == nil {
			set = make(map[uint32]struct{})
			n.grams[g] = set
		}
		set[id] = struct{}{}
	}
}

// Count 返回已索引条目数。
func (n *NameIndex) Count() int64 {
	n.mu.RLock()
	defer n.mu.RUnlock()
	return int64(len(n.docs))
}

// LookupPath 返回已索引条目的大小与修改时间（增量扫描的变更检测依据）。
func (n *NameIndex) LookupPath(path string) (size, mtime int64, ok bool) {
	n.mu.RLock()
	defer n.mu.RUnlock()
	id, ok := n.byPath[path]
	if !ok {
		return 0, 0, false
	}
	d := n.docs[id]
	return d.size, d.mtime, true
}

// RemovePath 从索引移除一个路径；返回是否存在。
func (n *NameIndex) RemovePath(path string) bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	id, ok := n.byPath[path]
	if !ok {
		return false
	}
	n.removeLocked(id)
	return true
}

// RemoveExcept 仅保留 seen 中的路径，其余全部移除（增量扫描的删除检测）。
// 返回被移除的路径列表（供上层联动清理全文库）。
func (n *NameIndex) RemoveExcept(seen map[string]struct{}) []string {
	n.mu.Lock()
	defer n.mu.Unlock()
	var removed []string
	for id, d := range n.docs {
		if _, keep := seen[d.path]; keep {
			continue
		}
		removed = append(removed, d.path)
		n.removeLocked(id)
	}
	return removed
}

// Reset 清空索引（"重建索引"入口）。
func (n *NameIndex) Reset() {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.docs = make(map[uint32]*docInfo)
	n.byPath = make(map[string]uint32)
	n.grams = make(map[string]map[uint32]struct{})
	n.next = 0
}

// Search 按子串查询名称，结果按"名称更短优先、修改时间更新优先"排序。
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
				Size: doc.size, ModTime: doc.mtime,
				Matched: "name", Kind: n.kind,
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

// removeLocked 删除指定 id 的文档及其全部倒排记录。调用方需持有写锁。
func (n *NameIndex) removeLocked(id uint32) {
	doc, ok := n.docs[id]
	if !ok {
		return
	}
	for _, g := range bigrams(doc.lower) {
		if set := n.grams[g]; set != nil {
			delete(set, id)
			if len(set) == 0 {
				delete(n.grams, g)
			}
		}
	}
	delete(n.docs, id)
	delete(n.byPath, doc.path)
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
