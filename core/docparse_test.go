package core

import (
	"archive/zip"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// makeDocx 在临时目录生成一个最小 .docx（zip + word/document.xml）。
func makeDocx(t *testing.T, dir, name, body string) string {
	t.Helper()
	p := filepath.Join(dir, name)
	f, err := os.Create(p)
	if err != nil {
		t.Fatal(err)
	}
	zw := zip.NewWriter(f)
	w, err := zw.Create("word/document.xml")
	if err != nil {
		t.Fatal(err)
	}
	_, err = w.Write([]byte(`<?xml version="1.0"?><w:document xmlns:w="urn:x"><w:body><w:p><w:r><w:t>` + body + `</w:t></w:r></w:p><w:p><w:r><w:t>第二段</w:t></w:r></w:p></w:body></w:document>`))
	if err != nil {
		t.Fatal(err)
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestExtractDocx(t *testing.T) {
	dir := t.TempDir()
	p := makeDocx(t, dir, "测试.docx", "你好搜索引擎")

	text, err := ExtractText(p)
	if err != nil {
		t.Fatalf("docx 抽取失败: %v", err)
	}
	if !strings.Contains(text, "你好搜索引擎") || !strings.Contains(text, "第二段") {
		t.Fatalf("抽取内容不完整: %q", text)
	}
}

// makeZip 生成含多个成员的最小 zip 文件（测试夹具）。
// members 为 member 与 body 交替出现的变长参数：member1, body1, member2, body2...
func makeZip(t *testing.T, path string, members ...string) {
	t.Helper()
	if len(members)%2 != 0 {
		t.Fatal("makeZip: 参数应为 member/body 成对出现")
	}
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	zw := zip.NewWriter(f)
	for i := 0; i < len(members); i += 2 {
		w, err := zw.Create(members[i])
		if err != nil {
			t.Fatal(err)
		}
		if _, err := w.Write([]byte(members[i+1])); err != nil {
			t.Fatal(err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestExtractXlsxAndPptx(t *testing.T) {
	dir := t.TempDir()

	// xlsx: sharedStrings
	xp := filepath.Join(dir, "t.xlsx")
	makeZip(t, xp, "xl/sharedStrings.xml",
		`<?xml version="1.0"?><sst xmlns="urn:x"><si><t>表格单元格内容</t></si><si><t>第二格</t></si></sst>`)

	text, err := ExtractText(xp)
	if err != nil || !strings.Contains(text, "表格单元格内容") {
		t.Fatalf("xlsx 抽取失败: %v %q", err, text)
	}

	// pptx: slides（乱序写入，验证按编号排序抽取）
	pp := filepath.Join(dir, "t.pptx")
	makeZip(t, pp,
		"ppt/slides/slide2.xml",
		`<?xml version="1.0"?><p:sld xmlns:p="urn:x"><p:sp><a:t xmlns:a="urn:y">第二页</a:t></p:sp></p:sld>`,
		"ppt/slides/slide1.xml",
		`<?xml version="1.0"?><p:sld xmlns:p="urn:x"><p:sp><a:t xmlns:a="urn:y">第一页</a:t></p:sp></p:sld>`,
	)

	text2, err := ExtractText(pp)
	if err != nil || !strings.Contains(text2, "第一页") || !strings.Contains(text2, "第二页") {
		t.Fatalf("pptx 抽取失败: %v %q", err, text2)
	}
	if strings.Index(text2, "第一页") > strings.Index(text2, "第二页") {
		t.Fatalf("幻灯片顺序错误: %q", text2)
	}
}

func TestExtractUnsupported(t *testing.T) {
	if _, err := ExtractText("/tmp/x.doc"); err != ErrUnsupportedFormat {
		t.Fatalf("应返回 ErrUnsupportedFormat, got %v", err)
	}
}
