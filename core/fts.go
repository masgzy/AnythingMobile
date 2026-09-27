package core

// 全文内容库（M3）：bleve v2 scorch 持久化索引 + gse 中文分词。
//
// 与 v0 内存实现的差异：
//   - 正文不再驻留内存（v0 为 64MB 上限的全量缓存）：文本作为 stored
//     字段由 scorch 压缩落盘，校验/摘要时按需读取命中文档；
//   - 索引持久化由 scorch 的 persister 异步完成，进程重启后直接可用；
//     崩溃丢失的最后一批文档由增量扫描的"名称在而全文缺失 → 补解析"
//     机制自愈（见 walker.go）；
//   - 文档 ID = 文件路径；idSet 维护一份路径集合缓存，支撑 Has/
//     RemoveExcept 的 O(1) 判定，启动时从索引真实枚举（单一事实源是
//     scorch 索引本身，快照 v3 不再保存正文）。
//
// 字段设计（见 ftsanalyzer.go）：
//   big   — unigram+bigram 索引：候选集收敛 + 连续 Position 邻接校验，
//           精确保留 v0 的"子串匹配"语义；
//   words — gse 分词索引：词边界相关性加权（排序质量）；
//   text  — 仅存储：精确校验与命中摘要的文本来源。
//
// 并发模型：scorch 支持 Batch 写与 Reader 读并发；idSet 用 RWMutex
// 保护。单进程内只允许一个 Engine（scorch 无跨进程文件锁）。

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"

	_ "embed"

	"github.com/blevesearch/bleve/v2"
	"github.com/blevesearch/bleve/v2/analysis/analyzer/custom"
	"github.com/blevesearch/bleve/v2/mapping"
	"github.com/go-ego/gse"
)

//go:embed data/dict_zh_min.txt
var dictZhMin string

const (
	ftsDirName = "fts"
	maxFTSText = 1 << 20 // 单文档入库文本上限（超出按 rune 截断；
	//                              与 docparse 的抽取上限一致，百万页图书亦够用；
	//                              更重要的是约束 scorch 索引时的瞬时内存）
	wordBonusScore = 4 // 词边界命中相对 bigram 命中的权重

	// boltLockTimeout scorch 底层 bbolt 的文件锁等待上限。
	// scorch（bbolt）对索引目录持有 flock：同目录出现第二个打开者时
	// 若无超时会永久阻塞（进程挂死）。正常使用下单进程单引擎，
	// 该超时只是双开/异常场景的兜底：超时报错 → 引擎降级，绝不挂死。
	boltLockTimeout = "3s"
)

// ---- gse 分词器单例（索引与查询两端共享；词典加载约 150ms，仅一次）----

var (
	segOnce   sync.Once
	segShared *gse.Segmenter
)

func sharedSegmenter() *gse.Segmenter {
	segOnce.Do(func() {
		seg := &gse.Segmenter{}
		// 词典加载失败不致命：words 字段自动降级为空（big 字段语义不受影响）
		_ = seg.LoadDictStr(dictZhMin)
		segShared = seg
	})
	return segShared
}

// registerFTSAnalyzers 在包初始化时注册自定义分词器（早于任何 bleve.Open）。
func init() { registerFTSAnalyzers() }

// ContentStore 全文内容库。对外方法集与 v0 内存版一致，Engine 无感替换。
type ContentStore struct {
	idx    bleve.Index
	idxDir string

	// broken 置位表示底层索引打开/创建失败：所有读写降级为无害空操作，
	// 搜索返回空、Add 报错，绝不 panic（gobind 约束）。
	broken atomic.Bool

	mu     sync.RWMutex
	idSet  map[string]struct{}
	closed atomic.Bool // Close 幂等保护（bleve 二次 Close 会 panic）
}

// NewContentStore 打开（或创建）持久化全文索引。
// dataDir 为空时使用临时目录（无持久化语义，供测试与降级场景）。
func NewContentStore(dataDir string) *ContentStore {
	c := &ContentStore{}
	sharedSegmenter() // 预热词典（约 150ms，一次性）

	dir := dataDir
	if dir == "" {
		tmp, err := os.MkdirTemp("", "anything-fts-")
		if err != nil {
			c.broken.Store(true)
			return c
		}
		dir = tmp
	}
	c.idxDir = filepath.Join(dir, ftsDirName)

	idx, err := openOrCreateFTS(c.idxDir)
	if err != nil {
		if isLockTimeoutErr(err) {
			c.broken.Store(true) // 目录被其他实例占用：降级，不破坏现场
			return c
		}
		// 索引目录损坏（半写状态等）：删除重建一次，仍失败则降级
		_ = os.RemoveAll(c.idxDir)
		if idx, err = openOrCreateFTS(c.idxDir); err != nil {
			c.broken.Store(true)
			return c
		}
	}
	c.idx = idx
	c.reloadIDSet()
	return c
}

// openOrCreateFTS 打开已有索引；不存在则按 FTS mapping 创建。
// 两路均携带 bolt 锁超时，索引目录被其他实例占用时报错而非阻塞。
func openOrCreateFTS(dir string) (bleve.Index, error) {
	if idx, err := bleve.OpenUsing(dir, map[string]interface{}{"bolt_timeout": boltLockTimeout}); err == nil {
		return idx, nil
	} else if isLockTimeoutErr(err) {
		return nil, err // 目录被占用：不重建，直接上报
	}
	if err := os.MkdirAll(filepath.Dir(dir), 0o755); err != nil {
		return nil, err
	}
	m, err := ftsMapping()
	if err != nil {
		return nil, err
	}
	return bleve.NewUsing(dir, m, bleve.Config.DefaultIndexType, bleve.Config.DefaultKVStore,
		map[string]interface{}{"bolt_timeout": boltLockTimeout})
}

// isLockTimeoutErr 判定是否为索引锁等待超时（区别于真实损坏：
// 锁超时绝不能走"删除重建"路径，否则会毁掉另一实例正在用的索引）。
func isLockTimeoutErr(err error) bool {
	return err != nil && strings.Contains(err.Error(), "timeout")
}

// ftsMapping 全文索引的字段映射（含自定义分析器注册）。
func ftsMapping() (mapping.IndexMapping, error) {
	im := bleve.NewIndexMapping()
	im.DefaultMapping.Dynamic = false

	// 自定义分析器：tokenizer 构造器已在 ftsanalyzer.go init 时注册进
	// 全局 registry；这里的 "type" 指向 bleve 内置 custom 组装器。
	if err := im.AddCustomAnalyzer("big_analyzer", map[string]interface{}{
		"type":      custom.Name,
		"tokenizer": bigramTokenizerName,
	}); err != nil {
		return nil, err
	}
	if err := im.AddCustomAnalyzer("words_analyzer", map[string]interface{}{
		"type":      custom.Name,
		"tokenizer": gseTokenizerName,
	}); err != nil {
		return nil, err
	}

	bigFM := bleve.NewTextFieldMapping()
	bigFM.Analyzer = "big_analyzer"
	bigFM.Store = false
	// 邻接校验走"存储文本 strings.Index"而非位置向量：
	// 关闭 term vectors 可省去海量位置数组（长文档 OOM 风险），
	// 倒排仅存词频，候选集收敛不受影响。
	bigFM.IncludeTermVectors = false
	bigFM.IncludeInAll = false

	wordsFM := bleve.NewTextFieldMapping()
	wordsFM.Analyzer = "words_analyzer"
	wordsFM.Store = false
	wordsFM.IncludeInAll = false

	textFM := bleve.NewTextFieldMapping()
	textFM.Store = true
	textFM.Index = false
	textFM.IncludeInAll = false

	doc := bleve.NewDocumentMapping()
	doc.Dynamic = false
	doc.AddFieldMappingsAt("big", bigFM)
	doc.AddFieldMappingsAt("words", wordsFM)
	doc.AddFieldMappingsAt("text", textFM)
	im.DefaultMapping = doc
	return im, nil
}

// Add 写入/更新一个文档的全文。空正文返回错误（与 v0 语义一致）。
func (c *ContentStore) Add(path, text string) error {
	if path == "" {
		return errors.New("core: path 为空")
	}
	text = normalizeForStore(text)
	if strings.TrimSpace(text) == "" {
		return errors.New("core: 文档正文为空")
	}
	if c.broken.Load() {
		return errors.New("core: 全文索引不可用")
	}
	if text = truncateRunes(text, maxFTSText); text == "" {
		return errors.New("core: 文档正文为空")
	}

	doc := map[string]interface{}{
		"big":   text,
		"words": text,
		"text":  text,
	}
	batch := c.idx.NewBatch()
	if err := batch.Index(path, doc); err != nil {
		return err
	}
	if err := c.idx.Batch(batch); err != nil {
		return err
	}
	c.mu.Lock()
	c.idSet[path] = struct{}{}
	c.mu.Unlock()
	return nil
}

// Remove 移除一个文档；返回是否存在。
func (c *ContentStore) Remove(path string) bool {
	if c.broken.Load() {
		return false
	}
	c.mu.Lock()
	_, ok := c.idSet[path]
	if ok {
		delete(c.idSet, path)
	}
	c.mu.Unlock()
	if !ok {
		return false
	}
	batch := c.idx.NewBatch()
	batch.Delete(path)
	_ = c.idx.Batch(batch) // 删除失败仅影响该条，不中断调用方
	return true
}

// RemoveExcept 仅保留 seen 中的文档，其余移除；返回被移除的路径数。
func (c *ContentStore) RemoveExcept(seen map[string]struct{}) int {
	if c.broken.Load() {
		return 0
	}
	c.mu.Lock()
	var victims []string
	for p := range c.idSet {
		if _, keep := seen[p]; !keep {
			victims = append(victims, p)
		}
	}
	for _, p := range victims {
		delete(c.idSet, p)
	}
	c.mu.Unlock()
	if len(victims) == 0 {
		return 0
	}
	batch := c.idx.NewBatch()
	for _, p := range victims {
		batch.Delete(p)
	}
	_ = c.idx.Batch(batch)
	return len(victims)
}

// Has 返回全文库中是否已存在该路径的正文（增量扫描补解析判据）。
func (c *ContentStore) Has(path string) bool {
	c.mu.RLock()
	defer c.mu.RUnlock()
	_, ok := c.idSet[path]
	return ok
}

// Count 返回已收录文档数。
func (c *ContentStore) Count() int64 {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return int64(len(c.idSet))
}

// Reset 清空并重建索引（"重建索引"入口）。
func (c *ContentStore) Reset() {
	if c.broken.Load() {
		return
	}
	_ = c.idx.Close()
	_ = os.RemoveAll(c.idxDir)
	idx, err := openOrCreateFTS(c.idxDir)
	if err != nil {
		c.broken.Store(true)
		c.mu.Lock()
		c.idSet = map[string]struct{}{}
		c.mu.Unlock()
		return
	}
	c.idx = idx
	c.closed.Store(false) // Reset 后可继续使用
	c.mu.Lock()
	c.idSet = map[string]struct{}{}
	c.mu.Unlock()
}

// Close 关闭底层索引（进程退出前或测试收尾调用；此后对象不可再用）。
// 幂等：重复调用无害。
func (c *ContentStore) Close() {
	if c.broken.Load() || !c.closed.CompareAndSwap(false, true) {
		return
	}
	_ = c.idx.Close()
}

// reloadIDSet 从索引真实枚举全部文档 ID（启动时的单一事实源校准）。
func (c *ContentStore) reloadIDSet() {
	set := make(map[string]struct{}, 1024)
	if c.broken.Load() {
		return
	}
	adv, err := c.idx.Advanced()
	if err != nil {
		return
	}
	reader, err := adv.Reader()
	if err != nil {
		return
	}
	defer reader.Close()
	idr, err := reader.DocIDReaderAll()
	if err != nil {
		return
	}
	defer idr.Close()
	for {
		iid, err := idr.Next()
		if err != nil || iid == nil {
			break
		}
		if ext, err := reader.ExternalID(iid); err == nil {
			set[string(ext)] = struct{}{}
		}
	}
	c.mu.Lock()
	c.idSet = set
	c.mu.Unlock()
}

// truncateRunes 按 rune 边界截断文本（多字节字符不产生半个字符）。
func truncateRunes(text string, maxBytes int) string {
	if len(text) <= maxBytes {
		return text
	}
	cut := maxBytes
	for cut > 0 && !utf8RuneStart(text[cut]) {
		cut--
	}
	return text[:cut]
}

func utf8RuneStart(b byte) bool { return b&0xC0 != 0x80 }
