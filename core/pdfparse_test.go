package core

// PDF 抽取测试：夹具由 scripts/gen_pdf_fixtures.py 生成（reportlab），
// 覆盖中文 CID 字体、英文 WinAnsi、多页三种形态。

import (
	"path/filepath"
	"strings"
	"testing"
)

func TestExtractPDFChinese(t *testing.T) {
	txt, err := extractPDF(filepath.Join("testdata", "sample_zh.pdf"))
	if err != nil {
		t.Fatalf("extractPDF: %v", err)
	}
	for _, want := range []string{"季度技术合作协议书", "智能文件搜索引擎", "合同有效期为两年"} {
		if !strings.Contains(txt, want) {
			t.Errorf("中文 PDF 应含 %q: %q", want, txt)
		}
	}
}

func TestExtractPDFEnglish(t *testing.T) {
	txt, err := extractPDF(filepath.Join("testdata", "sample_en.pdf"))
	if err != nil {
		t.Fatalf("extractPDF: %v", err)
	}
	for _, want := range []string{"Quarterly Machine Learning Report", "full-text indexing"} {
		if !strings.Contains(txt, want) {
			t.Errorf("英文 PDF 应含 %q: %q", want, txt)
		}
	}
}

func TestExtractPDFMultiPage(t *testing.T) {
	txt, err := extractPDF(filepath.Join("testdata", "sample_multi.pdf"))
	if err != nil {
		t.Fatalf("extractPDF: %v", err)
	}
	for _, want := range []string{"PAGE1MARK", "PAGE2MARK", "PAGE3MARK"} {
		if !strings.Contains(txt, want) {
			t.Errorf("多页 PDF 应含 %q: %q", want, txt)
		}
	}
}

// extractPDF 全链路：ExtractText 分发 + 入库可搜。
func TestPDFEndToEnd(t *testing.T) {
	src := t.TempDir()
	p := filepath.Join(src, "季度协议.pdf")
	// 夹具复制到临时目录（模拟真实扫描路径）
	if data, err := testdataRead("sample_zh.pdf"); err != nil {
		t.Fatal(err)
	} else if err := writeFixtureFile(p, data); err != nil {
		t.Fatal(err)
	}

	text, err := ExtractText(p)
	if err != nil {
		t.Fatalf("ExtractText(.pdf): %v", err)
	}
	if !strings.Contains(text, "季度技术合作协议书") {
		t.Fatalf("正文抽取异常: %q", text)
	}

	cs := ftsStore(t, "")
	if err := cs.Add(p, text); err != nil {
		t.Fatal(err)
	}
	hits, _ := cs.searchAll("合作协议", 0)
	if len(hits) != 1 {
		t.Fatalf("PDF 正文应可搜: %v", hits)
	}
	if hits[0].Snippet == "" {
		t.Fatal("摘要不应为空")
	}
}

// utf16BESafe：奇数字节串、非法代理对。
func TestUtf16BESafe(t *testing.T) {
	if got := utf16BESafe(""); got != "" {
		t.Errorf("空串: %q", got)
	}
	if got := utf16BESafe("\x00"); got != "" {
		t.Errorf("奇数尾字节应丢弃: %q", got)
	}
	// "中" = U+4E2D → 0x4E 0x2D；"文" = U+6587 → 0x65 0x87
	if got := utf16BESafe("\x4e\x2d\x65\x87"); got != "中文" {
		t.Errorf("UTF-16BE 解码错误: %q", got)
	}
}
