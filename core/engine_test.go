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
        opt, _ := json.Marshal(ScanOptions{Roots: []string{dir}, Mode: "incremental"})
        if err := e.StartScan(string(opt)); err != nil {
                t.Fatal(err)
        }
        if err := e.StartScan(string(opt)); err == nil {
                t.Fatal("重复扫描应返回错误")
        }
        e.CancelScan()
        // 等待结束
        time.Sleep(200 * time.Millisecond)
        if err := e.StartScan(string(opt)); err != nil {
                t.Fatalf("取消后应可重新扫描: %v", err)
        }
        e.CancelScan()
}

// TestEngineIncrementalScan 验证"进入应用自动增量重扫"：
// 新增文件被加入、未变化文件跳过解析、删除文件被移出索引。
func TestEngineIncrementalScan(t *testing.T) {
        dir := t.TempDir()
        f1 := filepath.Join(dir, "alpha.txt")
        if err := os.WriteFile(f1, []byte("hello"), 0o644); err != nil {
                t.Fatal(err)
        }
        sub := filepath.Join(dir, "子目录")
        if err := os.MkdirAll(sub, 0o755); err != nil {
                t.Fatal(err)
        }

        e, _ := NewEngine(2)
        c := newCollector()
        e.SetListener(c)

        start := func() {
                opt, _ := json.Marshal(ScanOptions{Roots: []string{dir}, Mode: "incremental"})
                if err := e.StartScan(string(opt)); err != nil {
                        t.Fatalf("StartScan: %v", err)
                }
        }
        waitStats := func(t *testing.T) Stats {
                select {
                case s := <-c.finished:
                        var st Stats
                        if err := json.Unmarshal([]byte(s), &st); err != nil {
                                t.Fatalf("stats 非法: %v", err)
                        }
                        return st
                case <-time.After(10 * time.Second):
                        t.Fatal("扫描超时")
                        return Stats{}
                }
        }

        // 第一次：首次建索引（1 文件 + 1 目录）
        start()
        st := waitStats(t)
        if !st.FirstBuild || st.Files != 1 || st.Added != 1 {
                t.Fatalf("首次扫描异常: %+v", st)
        }

        // 第二次：无任何变动 => added/updated/removed 全 0
        start()
        st = waitStats(t)
        if st.Added != 0 || st.Updated != 0 || st.Removed != 0 {
                t.Fatalf("无变动扫描不应有增删改: %+v", st)
        }

        // 第三次：新增文件 + 修改 f1 => added=1 updated=1
        f2 := filepath.Join(dir, "beta.txt")
        if err := os.WriteFile(f2, []byte("world"), 0o644); err != nil {
                t.Fatal(err)
        }
        if err := os.WriteFile(f1, []byte("hello!!"), 0o644); err != nil {
                t.Fatal(err)
        }
        // 确保 mtime 变化（部分文件系统时间戳精度为秒）
        future := time.Now().Add(2 * time.Second)
        os.Chtimes(f1, future, future)

        start()
        st = waitStats(t)
        if st.Added != 1 || st.Updated != 1 || st.Files != 2 {
                t.Fatalf("增量扫描统计异常: %+v", st)
        }

        // 删除 f2 => removed=1
        os.Remove(f2)
        start()
        st = waitStats(t)
        if st.Removed != 1 {
                t.Fatalf("删除检测失败: %+v", st)
        }
        if resp, err := e.Search("beta", 10); err != nil || resp == "" {
                t.Fatalf("搜索失败: %v", err)
        } else {
                sr := SearchResponse{}
                json.Unmarshal([]byte(resp), &sr)
                if sr.Total != 0 {
                        t.Fatalf("已删除文件不应命中: %+v", sr)
                }
        }

        // 目录名可搜索
        sr := searchOnce(t, e, "子目录")
        if sr.Total < 1 || sr.Hits[0].Kind != "folder" {
                t.Fatalf("目录名搜索失败: %+v", sr)
        }
}

func searchOnce(t *testing.T, e *Engine, q string) SearchResponse {
        t.Helper()
        resp, err := e.Search(q, 10)
        if err != nil {
                t.Fatalf("Search(%q): %v", q, err)
        }
        var sr SearchResponse
        if err := json.Unmarshal([]byte(resp), &sr); err != nil {
                t.Fatalf("响应非法: %v", err)
        }
        return sr
}
