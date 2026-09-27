package core

// 外部扩展索引（external.go）专项测试：
// Shizuku/Stellar 特权通道收录的条目与常规索引隔离、可搜索、
// 随快照持久化、且不被增量扫描的 RemoveExcept 误删。

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// writeFile 测试助手：兼容 string / []byte 两种内容形态。
func writeFile(dir, name string, data any) error {
	var b []byte
	switch v := data.(type) {
	case string:
		b = []byte(v)
	case []byte:
		b = v
	default:
		panic("writeFile: 不支持的内容类型")
	}
	return os.WriteFile(filepath.Join(dir, name), b, 0o644)
}

func feedJSON(t *testing.T, root string, dirs, files []string) string {
	t.Helper()
	b, err := json.Marshal(ExternalFeed{Root: root, Dirs: dirs, Files: files})
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestEngineExternalEntries(t *testing.T) {
	e, err := NewEngine(2, "")
	if err != nil {
		t.Fatal(err)
	}
	root := "/storage/emulated/0/Android/data"
	if err := e.ReplaceExternalEntries(feedJSON(t, root,
		[]string{root, root + "/com.tencent.mm"},
		[]string{root + "/com.tencent.mm/MicroMsg/StampedDB.txt"},
	)); err != nil {
		t.Fatalf("ReplaceExternalEntries: %v", err)
	}

	if st := e.currentStats(false); st.External != 3 {
		t.Fatalf("External 计数应为 3, got %d", st.External)
	}

	// 文件名搜索：kind=file
	sr := searchOnce(t, e, "StampedDB")
	if sr.Total != 1 || sr.Hits[0].Kind != "file" || sr.Hits[0].Path != root+"/com.tencent.mm/MicroMsg/StampedDB.txt" {
		t.Fatalf("外部文件搜索异常: %+v", sr)
	}
	// 目录名搜索：kind=folder
	sr = searchOnce(t, e, "com.tencent.mm")
	if sr.Total != 1 || sr.Hits[0].Kind != "folder" {
		t.Fatalf("外部目录搜索异常: %+v", sr)
	}

	// 全量替换：已消失条目应被清除
	if err := e.ReplaceExternalEntries(feedJSON(t, root,
		[]string{root},
		[]string{root + "/com.spotify/music-cache.dat"},
	)); err != nil {
		t.Fatal(err)
	}
	if st := e.currentStats(false); st.External != 2 {
		t.Fatalf("替换后 External 计数应为 2, got %d", st.External)
	}
	if sr := searchOnce(t, e, "StampedDB"); sr.Total != 0 {
		t.Fatalf("替换后旧条目不应命中: %+v", sr)
	}
	if sr := searchOnce(t, e, "music-cache"); sr.Total != 1 {
		t.Fatalf("替换后新条目应命中: %+v", sr)
	}

	// 清空（用户关闭开关）：空清单即全清
	if err := e.ReplaceExternalEntries(feedJSON(t, root, nil, nil)); err != nil {
		t.Fatal(err)
	}
	if st := e.currentStats(false); st.External != 0 {
		t.Fatalf("清空后 External 应为 0, got %d", st.External)
	}
}

func TestEngineExternalInvalidInput(t *testing.T) {
	e, _ := NewEngine(2, "")
	if err := e.ReplaceExternalEntries("{not json"); err == nil {
		t.Fatal("非法 JSON 应返回错误")
	}
	// 越出根目录 / 含换行 / 空串的路径必须被过滤
	root := "/storage/emulated/0/Android/data"
	if err := e.ReplaceExternalEntries(feedJSON(t, root,
		[]string{"/etc", root + "/ok\nbad", ""},
		[]string{root + "/../secret", root + "/good.db"},
	)); err != nil {
		t.Fatal(err)
	}
	if st := e.currentStats(false); st.External != 1 {
		t.Fatalf("非法路径应被过滤只剩 1 条, got %d", st.External)
	}
	sr := searchOnce(t, e, "good.db")
	if sr.Total != 1 {
		t.Fatalf("合法路径应保留: %+v", sr)
	}
}

// 关键回归：外部条目绝不能被常规扫描的 RemoveExcept 清掉。
func TestEngineExternalSurvivesIncrementalScan(t *testing.T) {
	dir := t.TempDir()
	if err := writeFile(dir, "local.txt", "本地文件"); err != nil {
		t.Fatal(err)
	}
	e, _ := NewEngine(2, "")
	c := newCollector()
	e.SetListener(c)

	root := "/storage/emulated/0/Android/data"
	if err := e.ReplaceExternalEntries(feedJSON(t, root,
		[]string{root + "/com.test"},
		[]string{root + "/com.test/外部缓存.dat"},
	)); err != nil {
		t.Fatal(err)
	}

	opt, _ := json.Marshal(ScanOptions{Roots: []string{dir}, Mode: "incremental"})
	if err := e.StartScan(string(opt)); err != nil {
		t.Fatal(err)
	}
	select {
	case s := <-c.finished:
		var st Stats
		if err := json.Unmarshal([]byte(s), &st); err != nil {
			t.Fatal(err)
		}
		// 外部条目不参与 first_build 判定：常规索引只有 1 个文件 => first_build
		if !st.FirstBuild || st.Files != 1 {
			t.Fatalf("常规扫描应 first_build 且 1 文件: %+v", st)
		}
		if st.External != 2 {
			t.Fatalf("增量扫描后外部条目应原样保留 2 条, got %d", st.External)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("扫描超时")
	}
	// 扫描收尾（RemoveExcept）后再搜，外部条目必须仍在
	if sr := searchOnce(t, e, "外部缓存"); sr.Total != 1 {
		t.Fatalf("外部条目被增量扫描误删: %+v", sr)
	}
	if sr := searchOnce(t, e, "local"); sr.Total != 1 {
		t.Fatalf("常规条目丢失: %+v", sr)
	}

	// RemovePaths 同时作用于外部索引（宿主删除联动）
	resp, err := e.RemovePaths(`["` + root + `/com.test/外部缓存.dat"]`)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(resp, `"removed":1`) {
		t.Fatalf("RemovePaths 应命中外部条目: %s", resp)
	}
	if sr := searchOnce(t, e, "外部缓存"); sr.Total != 0 {
		t.Fatalf("RemovePaths 后外部条目应被移除: %+v", sr)
	}
}

// 扫描进行中拒绝替换外部索引（宿主应等 onFinished 后再喂）。
func TestEngineExternalRejectedWhileScanning(t *testing.T) {
	dir := t.TempDir()
	if err := writeFile(dir, "legacy.doc", []byte("x")); err != nil {
		t.Fatal(err)
	}
	e, _ := NewEngine(2, "")
	c := newCollector()
	e.SetListener(c)
	parser := blockingParser{entered: make(chan struct{}, 1), release: make(chan struct{})}
	e.SetExternalParser(parser)

	opt, _ := json.Marshal(ScanOptions{Roots: []string{dir}, Mode: "incremental"})
	if err := e.StartScan(string(opt)); err != nil {
		t.Fatal(err)
	}
	<-parser.entered // 已卡在解析器：必然处于扫描中
	if err := e.ReplaceExternalEntries(feedJSON(t, "/x", nil, nil)); err == nil {
		t.Fatal("扫描进行中应拒绝替换")
	}
	close(parser.release)
	select {
	case <-c.finished:
	case <-time.After(5 * time.Second):
		t.Fatal("扫描未收尾")
	}
}

// 外部条目随快照持久化：进程"重启"后无需重新喂入即可搜索；
// 且不参与 first_build（恢复后 indexed 仍为 0）。
func TestEngineExternalPersistence(t *testing.T) {
	dataDir := t.TempDir()
	e1, err := NewEngine(2, dataDir)
	if err != nil {
		t.Fatal(err)
	}
	root := "/storage/emulated/0/Android/data"
	if err := e1.ReplaceExternalEntries(feedJSON(t, root,
		[]string{root + "/org.mozilla.firefox"},
		[]string{root + "/org.mozilla.firefox/cache/webviewCache.bin"},
	)); err != nil {
		t.Fatal(err)
	}
	// 替换触发异步落盘：屏障等待
	e1.saveWG.Wait()

	e2, err := NewEngine(2, dataDir)
	if err != nil {
		t.Fatal(err)
	}
	var pre Stats
	if err := json.Unmarshal([]byte(e2.Stats()), &pre); err != nil {
		t.Fatal(err)
	}
	if pre.External != 2 || pre.Indexed != 0 {
		t.Fatalf("恢复后应 external=2 indexed=0: %+v", pre)
	}
	sr := searchOnce(t, e2, "webviewCache")
	if sr.Total != 1 || sr.Hits[0].Kind != "file" {
		t.Fatalf("快照恢复后外部条目应可搜索: %+v", sr)
	}
	e1.saveWG.Wait()
	e2.saveWG.Wait()
}
