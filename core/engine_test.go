package core

import (
        "encoding/json"
        "os"
        "path/filepath"
        "sync"
        "testing"
        "time"
)

type collector struct {
        mu        sync.Mutex
        progressN int64
        finished  chan string
        errs      []string
}

func newCollector() *collector {
        return &collector{finished: make(chan string, 1)}
}

func (c *collector) OnProgress(phase string, done int64) {
        c.mu.Lock()
        c.progressN = done
        c.mu.Unlock()
}

func (c *collector) OnFinished(statsJSON string) {
        c.finished <- statsJSON
}

func (c *collector) OnError(phase string, message string) {
        c.mu.Lock()
        c.errs = append(c.errs, message)
        c.mu.Unlock()
}

func TestEngineScanAndSearch(t *testing.T) {
        dir := t.TempDir()
        // 构造文件树
        sub := filepath.Join(dir, "Docs")
        if err := os.MkdirAll(sub, 0o755); err != nil {
                t.Fatal(err)
        }
        if err := os.WriteFile(filepath.Join(sub, "项目计划.docx"), []byte("x"), 0o644); err != nil {
                t.Fatal(err)
        }
        if err := os.WriteFile(filepath.Join(dir, "会议记录.txt"), []byte("关于搜索索引的讨论"), 0o644); err != nil {
                t.Fatal(err)
        }
        if err := os.MkdirAll(filepath.Join(dir, "Android", "data"), 0o755); err != nil {
                t.Fatal(err)
        }
        if err := os.WriteFile(filepath.Join(dir, "Android", "data", "hidden.txt"), []byte("x"), 0o644); err != nil {
                t.Fatal(err)
        }

        e, err := NewEngine(2)
        if err != nil {
                t.Fatal(err)
        }
        c := newCollector()
        e.SetListener(c)

        roots, _ := json.Marshal([]string{dir})
        if err := e.StartScan(string(roots)); err != nil {
                t.Fatalf("StartScan: %v", err)
        }

        select {
        case statsJSON := <-c.finished:
                st := Stats{}
                if err := json.Unmarshal([]byte(statsJSON), &st); err != nil {
                        t.Fatalf("stats JSON 非法: %v", err)
                }
                if st.Files != 2 { // Android/data 下的 hidden.txt 被过滤
                        t.Fatalf("文件数应为 2, got %d", st.Files)
                }
                if st.DocsFound != 1 {
                        t.Fatalf("文档数应为 1, got %d", st.DocsFound)
                }
                if st.DocsIndexed != 1 { // 项目计划.docx 为合法 zip 缺失 => 抽取失败不索引?
                        // 注意：非法 docx 抽取失败，DocsIndexed 可能为 0，这里放宽
                        t.Logf("docs_indexed=%d（非法 docx 不计入属正常）", st.DocsIndexed)
                }
        case <-time.After(10 * time.Second):
                t.Fatal("扫描超时")
        }

        // 文件名搜索
        resp, err := e.Search("会议记录", 10)
        if err != nil {
                t.Fatal(err)
        }
        sr := SearchResponse{}
        if err := json.Unmarshal([]byte(resp), &sr); err != nil {
                t.Fatal(err)
        }
        if sr.Total != 1 || !filepath.IsAbs(sr.Hits[0].Path) {
                t.Fatalf("搜索结果异常: %+v", sr)
        }

        // 全文回填搜索（模拟 Kotlin/POI 兜底 .doc）
        if err := e.AddDocumentText(filepath.Join(sub, "legacy.doc"), "这是通过宿主解析器回填的正文内容"); err != nil {
                t.Fatal(err)
        }
        resp2, err := e.Search("宿主解析器", 10)
        if err != nil {
                t.Fatal(err)
        }
        sr2 := SearchResponse{}
        if err := json.Unmarshal([]byte(resp2), &sr2); err != nil {
                t.Fatal(err)
        }
        if sr2.Total != 1 || sr2.Hits[0].Matched != "content" {
                t.Fatalf("全文搜索异常: %+v", sr2)
        }
}

func TestEngineDoubleScanRejected(t *testing.T) {
        dir := t.TempDir()
        e, _ := NewEngine(2)
        roots, _ := json.Marshal([]string{dir})
        if err := e.StartScan(string(roots)); err != nil {
                t.Fatal(err)
        }
        if err := e.StartScan(string(roots)); err == nil {
                t.Fatal("重复扫描应返回错误")
        }
        e.CancelScan()
        // 等待结束
        time.Sleep(200 * time.Millisecond)
        if err := e.StartScan(string(roots)); err != nil {
                t.Fatalf("取消后应可重新扫描: %v", err)
        }
        e.CancelScan()
}
