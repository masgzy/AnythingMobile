package core

import (
	"strings"
	"testing"
	"unicode/utf8"
)

// TestSanitizeText 覆盖菱形字符三类来源与合法内容保真。
func TestSanitizeText(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		{"干净文本原样", "中文 English 123 ✓\t\ntab", "中文 English 123 ✓\t\ntab"},
		{"无效UTF8字节丢弃", "ab\xffcd", "abcd"},
		{"截断多字节序列丢弃", "汉\xe4\xb8", "汉"},
		{"竖直制表符归一换行", "a\x0bb", "a\nb"},
		{"换页符归一换行", "a\x0cb", "a\nb"},
		{"U+2028归一换行", "a\u2028b", "a\nb"},
		{"空字符丢弃", "a\x00b", "ab"},
		{"退格符丢弃", "a\x08b", "ab"},
		{"DEL丢弃", "a\x7fb", "ab"},
		{"C1控制符丢弃", "a\u0093b", "ab"},
		{"PUA符号字体丢弃", "项目\uF0B7要点", "项目要点"},
		{"非字符FDD0丢弃", "a\uFDD0b", "ab"},
		{"替换字符丢弃", "a\uFFFD\nb", "a\nb"},
		{"平面尾非字符丢弃", "a\uFFFEb\U0010FFFEc", "abc"},
		{"混合真实场景", "标题\uF0A7\x0b正文\x01\x02继续\xff", "标题\n正文继续"},
		{"emoji保留", "a\U0001F600b", "a\U0001F600b"},
	}
	for _, c := range cases {
		got := sanitizeText(c.in)
		if got != c.want {
			t.Errorf("%s: sanitizeText(%q) = %q, want %q", c.name, c.in, got, c.want)
		}
		if !utf8.ValidString(got) {
			t.Errorf("%s: 输出非合法 UTF-8", c.name)
		}
	}
}

// TestNormalizeForStore 验证 \r\n 归一与组合清洗。
func TestNormalizeForStore(t *testing.T) {
	in := "第一行\r\n第二行\r第三行\uF0B7\x0b第四行\xff尾"
	want := "第一行\n第二行\n第三行\n第四行尾"
	if got := normalizeForStore(in); got != want {
		t.Errorf("normalizeForStore = %q, want %q", got, want)
	}
}

// TestSnippetRuneAlignment 摘要窗口边界落在多字节字符中间时不得产生非法 UTF-8。
func TestSnippetRuneAlignment(t *testing.T) {
	// 构造：40 字节偏移处命中，窗口 start/end 均可能切进汉字中间
	// "汉"=3 字节；前置 41 字节使 start=1（非 rune 起点）
	prefix := strings.Repeat("a", 41) // 41 字节 ASCII
	target := "汉字测试关键词"
	text := prefix + strings.Repeat("汉", 50) + target + strings.Repeat("字", 50)
	idx := strings.Index(text, target)
	if idx < 0 {
		t.Fatal("构造失败：未找到目标串")
	}
	s := snippet(text, idx, len(target))
	if !utf8.ValidString(s) {
		t.Fatalf("snippet 产出非法 UTF-8: %q", s)
	}
	if !strings.Contains(s, target) {
		t.Errorf("snippet 丢失命中词: %q", s)
	}
	// 命中位于文本头部：start 对齐后仍需完整包含命中词
	head := "汉" + strings.Repeat("字", 20) + "关键词结尾"
	hIdx := strings.Index(head, "关键词")
	s2 := snippet(head, hIdx, len("关键词"))
	if !utf8.ValidString(s2) || !strings.Contains(s2, "关键词") {
		t.Errorf("头部命中摘要异常: %q", s2)
	}
	// 偏移异常退化路径：不得 panic，输出合法 UTF-8
	s3 := snippet(text, 999999, 10)
	if !utf8.ValidString(s3) {
		t.Errorf("偏移异常路径产出非法 UTF-8: %q", s3)
	}
}

// TestContentStoreToLowerOffsetFallback 大小写转换改变字节长度时摘要不越界不乱码。
func TestContentStoreToLowerOffsetFallback(t *testing.T) {
	cs := NewContentStore(t.TempDir())
	// U+0130 (İ) ToLower 后变为 i+U+0307，字节长度 2→3
	orig := "İ" + strings.Repeat("甲", 45) + "needle" + strings.Repeat("乙", 45)
	if err := cs.Add("/t.docx", orig); err != nil {
		t.Fatalf("Add: %v", err)
	}
	lower, _ := cs.searchAll("needle", 0)
	if len(lower) != 1 {
		t.Fatalf("应命中 1 条, got %d", len(lower))
	}
	if !utf8.ValidString(lower[0].Snippet) {
		t.Errorf("摘要么法 UTF-8: %q", lower[0].Snippet)
	}
	if !strings.Contains(lower[0].Snippet, "needle") {
		t.Errorf("摘要丢失命中词: %q", lower[0].Snippet)
	}
}

// TestContentStoreSanitizeOnAdd 入库统一清洗：PUA/控制符不进内容库。
func TestContentStoreSanitizeOnAdd(t *testing.T) {
	cs := NewContentStore(t.TempDir())
	if err := cs.Add("/x.docx", "正文\uF0B7带符号\x0b继续"); err != nil {
		t.Fatalf("Add: %v", err)
	}
	hits, _ := cs.searchAll("正文", 0)
	if len(hits) != 1 {
		t.Fatalf("应命中, got %d", len(hits))
	}
	if strings.ContainsRune(hits[0].Snippet, 0xF0B7) || strings.ContainsRune(hits[0].Snippet, '\v') {
		t.Errorf("摘要含未清洗字符: %q", hits[0].Snippet)
	}
	// v3：原文存储于 bleve stored 字段，读取后校验清洗确实生效
	if stored, ok := fetchStored(t, cs, "/x.docx"); !ok {
		t.Fatalf("存储正文缺失")
	} else if strings.ContainsRune(stored, 0xF0B7) {
		t.Errorf("存储正文含 PUA 字符")
	}
}

// fetchStored 从 FTS 索引读取文档存储正文（测试白盒辅助）。
func fetchStored(t *testing.T, cs *ContentStore, path string) (string, bool) {
	t.Helper()
	adv, err := cs.idx.Advanced()
	if err != nil {
		t.Fatalf("Advanced: %v", err)
	}
	reader, err := adv.Reader()
	if err != nil {
		t.Fatalf("Reader: %v", err)
	}
	defer reader.Close()
	return fetchStoredText(reader, path)
}
