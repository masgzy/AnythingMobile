package core

// 解析流水线（parsePool）行为测试：
//   - 遍历与解析解耦后，OnFinished 时全文库必须已包含全部文档
//     （traverse 收尾等待队列排空的保证）；
//   - 取消扫描时流水线快速排空，不悬挂；
//   - 增量重扫的 RemoveExcept 不会误删流水线刚写入的全文。

import (
	"archive/zip"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// makeDocxRaw 生成最小 docx（不经 testing.T，便于批量构造）。
func makeDocxRaw(dir, name, body string) (string, error) {
	p := filepath.Join(dir, name)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	f, err := os.Create(p)
	if err != nil {
		return "", err
	}
	defer f.Close()
	zw := zip.NewWriter(f)
	w, err := zw.Create("word/document.xml")
	if err != nil {
		return "", err
	}
	if _, err := w.Write([]byte(`<?xml version="1.0"?><w:document xmlns:w="urn:x"><w:body><w:p><w:r><w:t>` + body + `</w:t></w:r></w:p></w:body></w:document>`)); err != nil {
		return "", err
	}
	if err := zw.Close(); err != nil {
		return "", err
	}
	return p, nil
}

func TestParsePoolDrainBeforeFinish(t *testing.T) {
	src := t.TempDir()
	// 三个子目录各放 5 篇文档，共 15 篇
	const total = 15
	for d := 0; d < 3; d++ {
		for i := 0; i < 5; i++ {
			if _, err := makeDocxRaw(filepath.Join(src, "目录"+string(rune('A'+d))),
				"文档"+string(rune('a'+i))+".docx", "流水线正文"+string(rune('a'+i))); err != nil {
				t.Fatal(err)
			}
		}
	}

	e, err := NewEngine(4, "")
	if err != nil {
		t.Fatal(err)
	}
	c := newCollector()
	e.SetListener(c)
	opt, _ := json.Marshal(ScanOptions{Roots: []string{src}, Mode: "full"})
	if err := e.StartScan(string(opt)); err != nil {
		t.Fatal(err)
	}

	select {
	case s := <-c.finished:
		var st Stats
		if err := json.Unmarshal([]byte(s), &st); err != nil {
			t.Fatal(err)
		}
		if st.DocsFound != total || st.DocsIndexed != total {
			t.Fatalf("文档统计异常: found=%d indexed=%d, 期望 %d", st.DocsFound, st.DocsIndexed, total)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("扫描超时")
	}

	// OnFinished 之后全文必须可搜索（排空先于收尾）
	sr := searchOnce(t, e, "流水线正文c")
	if sr.TotalContent < 1 {
		t.Fatalf("收尾后全文缺失: %+v", sr)
	}

	// 增量重扫（无变动）：全文不得被 RemoveExcept 误删
	c2 := newCollector()
	e.SetListener(c2)
	opt2, _ := json.Marshal(ScanOptions{Roots: []string{src}, Mode: "incremental"})
	if err := e.StartScan(string(opt2)); err != nil {
		t.Fatal(err)
	}
	select {
	case <-c2.finished:
	case <-time.After(10 * time.Second):
		t.Fatal("增量扫描超时")
	}
	sr = searchOnce(t, e, "流水线正文a")
	if sr.TotalContent < 1 {
		t.Fatalf("增量重扫后全文被误删: %+v", sr)
	}
}

func TestParsePoolCancel(t *testing.T) {
	src := t.TempDir()
	for i := 0; i < 40; i++ {
		if _, err := makeDocxRaw(src, "文档"+string(rune('a'+i%26))+string(rune('0'+i/26))+".docx", "取消测试正文"); err != nil {
			t.Fatal(err)
		}
	}

	e, err := NewEngine(2, "")
	if err != nil {
		t.Fatal(err)
	}
	c := newCollector()
	e.SetListener(c)
	opt, _ := json.Marshal(ScanOptions{Roots: []string{src}, Mode: "full"})
	if err := e.StartScan(string(opt)); err != nil {
		t.Fatal(err)
	}
	// 遍历与解析都在飞时取消：必须快速收尾且不悬挂
	e.CancelScan()
	select {
	case s := <-c.finished:
		var st Stats
		if err := json.Unmarshal([]byte(s), &st); err != nil {
			t.Fatal(err)
		}
		if !st.Cancelled {
			t.Fatalf("stats 应标记 cancelled: %+v", st)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("取消后未收尾")
	}
	if e.IsScanning() {
		t.Fatal("取消后不应仍在扫描")
	}
	// 取消后应可重新扫描
	if err := e.StartScan(string(opt)); err != nil {
		t.Fatalf("取消后应可重新扫描: %v", err)
	}
	e.CancelScan()
	select {
	case <-c.finished:
	case <-time.After(5 * time.Second):
		t.Fatal("二次扫描未收尾")
	}
}

func TestParseWorkersFor(t *testing.T) {
	cases := map[int]int{1: 1, 2: 1, 3: 2, 4: 2, 8: 4, 16: 6, 0: 1, -3: 1}
	for in, want := range cases {
		if got := parseWorkersFor(in); got != want {
			t.Fatalf("parseWorkersFor(%d)=%d, want %d", in, got, want)
		}
	}
}
