package core

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// ingestEntries 构造 Ingest 入参 JSON。
func ingestEntries(entries ...IngestEntry) string {
	b, err := json.Marshal(entries)
	if err != nil {
		panic(err)
	}
	return string(b)
}

func TestEngineIngestNamesAndContent(t *testing.T) {
	e, err := NewEngine(2, "")
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	c := newCollector()
	e.SetListener(c)

	// SAF 模式：文件路径可能是 content URI，名称必须用显式 Name
	entries := []IngestEntry{
		{Path: "content://abc/document/primary%3ADownload%2F%E6%8A%A5%E5%91%8A.docx", Name: "报告.docx", Kind: "file",
			Content: "包含季度销售数据与预算的正文内容"},
		{Path: "content://abc/document/primary%3APictures", Name: "Pictures", Kind: "folder"},
		{Path: "content://abc/document/primary%3Anotes.txt", Name: "notes.txt", Kind: "file"},
	}
	if _, err := e.Ingest(ingestEntries(entries...)); err != nil {
		t.Fatalf("Ingest: %v", err)
	}

	// 名称命中：显式 Name 参与检索
	hits := e.names.Search("报告", 10)
	if len(hits) != 1 || hits[0].Name != "报告.docx" {
		t.Fatalf("名称检索失败: %+v", hits)
	}
	// 目录命中
	if dirs := e.dirs.Search("Pictures", 10); len(dirs) != 1 {
		t.Fatalf("目录检索失败: %+v", dirs)
	}
	// 全文命中（bleve 批量入库）
	if chits, _ := e.content.searchAll("季度销售", 100); len(chits) != 1 {
		t.Fatalf("全文检索失败: %+v", chits)
	}
	// 统计：files 只数文件条目（与扫描口径一致），docs 只含提供正文的
	if st := e.currentStats(false); st.Files != 2 || st.Added != 2 || st.DocsFound != 1 || st.DocsIndexed != 1 {
		t.Fatalf("统计不符: %+v", st)
	}
	// OnFinished 回调异步送达（与扫描路径同语义），带超时等待
	select {
	case statsJSON := <-c.finished:
		st := Stats{}
		if err := json.Unmarshal([]byte(statsJSON), &st); err != nil {
			t.Fatalf("stats JSON 非法: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatalf("Ingest 未回调 OnFinished")
	}
}

func TestEngineIngestRebuildResetsStale(t *testing.T) {
	e, err := NewEngine(2, "")
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	c := newCollector()
	e.SetListener(c)

	if _, err := e.Ingest(ingestEntries(
		IngestEntry{Path: "content://a/1.txt", Name: "1.txt", Kind: "file", Content: "第一版正文内容"},
		IngestEntry{Path: "content://a/2.txt", Name: "2.txt", Kind: "file"},
	)); err != nil {
		t.Fatal(err)
	}

	// 第二轮注入不含 1.txt：整体重建后旧条目（含全文）必须消失
	if _, err := e.Ingest(ingestEntries(
		IngestEntry{Path: "content://a/2.txt", Name: "2.txt", Kind: "file"},
		IngestEntry{Path: "content://a/3.txt", Name: "3.txt", Kind: "file"},
	)); err != nil {
		t.Fatal(err)
	}

	if hits := e.names.Search("1.txt", 10); len(hits) != 0 {
		t.Fatalf("旧条目未被清除: %+v", hits)
	}
	if e.content.Count() != 0 {
		t.Fatalf("旧全文未被清除: %d", e.content.Count())
	}
	if n := e.names.Count(); n != 2 {
		t.Fatalf("现存文件条目应为 2，实际 %d", n)
	}
	// 新注入的全文可检索（3.txt 无正文，1.txt 已清除）
	if chits, _ := e.content.searchAll("第一版", 100); len(chits) != 0 {
		t.Fatalf("旧全文残留: %+v", chits)
	}
	// 第二轮结束后 OnFinished 已通知两次，带超时消费避免 channel 满
	select {
	case <-c.finished:
	case <-time.After(5 * time.Second):
		t.Fatalf("第二轮 OnFinished 未送达")
	}
}

func TestEngineIngestThenFullScanNoStaleContent(t *testing.T) {
	dir := t.TempDir()
	e, err := NewEngine(2, "")
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	c := newCollector()
	e.SetListener(c)

	if _, err := e.Ingest(ingestEntries(
		IngestEntry{Path: "content://a/old.txt", Name: "old.txt", Kind: "file", Content: "旧范围的全文内容"},
	)); err != nil {
		t.Fatal(err)
	}

	// 切回物理路径 full 扫描：全文库必须一并重置（范围切换不残留）
	f := filepath.Join(dir, "new.txt")
	if err := os.WriteFile(f, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	roots, _ := json.Marshal([]string{dir})
	if err := e.StartScan(string(roots)); err != nil {
		t.Fatalf("StartScan: %v", err)
	}
	select {
	case <-c.finished:
	case <-time.After(10 * time.Second):
		t.Fatalf("full 扫描未收尾")
	}
	if hits, _ := e.content.searchAll("旧范围", 100); len(hits) != 0 {
		t.Fatalf("full 扫描后旧全文残留: %+v", hits)
	}
	if hits := e.names.Search("old.txt", 10); len(hits) != 0 {
		t.Fatalf("full 扫描后旧名称残留: %+v", hits)
	}
}

func TestEngineIngestBusyRejected(t *testing.T) {
	e, err := NewEngine(2, "")
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	// 直接占用 scanning 标记模拟扫描进行中
	if !e.scanning.CompareAndSwap(false, true) {
		t.Fatal("标记置位失败")
	}
	defer e.scanning.Store(false)
	if _, err := e.Ingest(ingestEntries(IngestEntry{Path: "content://a/x", Name: "x", Kind: "file"})); err == nil {
		t.Fatal("扫描进行中时 Ingest 应被拒绝")
	}
	// 非法 JSON 同样拒绝
	if _, err := e.Ingest("{not-json"); err == nil {
		t.Fatal("非法 JSON 应返回错误")
	}
}

func TestEngineExtractDocFromBytes(t *testing.T) {
	e, err := NewEngine(2, "")
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()

	// 真实 PDF 字节流：落临时文件走完整解析分发
	data, err := os.ReadFile(filepath.Join("testdata", "sample_zh.pdf"))
	if err != nil {
		t.Fatal(err)
	}
	text, err := e.ExtractDoc("文档.pdf", data)
	if err != nil {
		t.Fatalf("ExtractDoc: %v", err)
	}
	if strings.TrimSpace(text) == "" {
		t.Fatal("PDF 正文抽取为空")
	}

	// 不支持的格式：返回空串而非报错（调用方按无全文处理）
	if text2, err := e.ExtractDoc("a.xyz", []byte("x")); err != nil || text2 != "" {
		t.Fatalf("不支持格式应返回空: %q %v", text2, err)
	}

	// 超解析上限：拒绝
	if _, err := e.ExtractDoc("big.pdf", make([]byte, maxParseSize+1)); err == nil {
		t.Fatal("超上限应报错")
	}
}
