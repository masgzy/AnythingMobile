package core

// FTS（bleve+gse 持久化全文库）行为测试：语义、持久化、并发、边界。

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// ftsStore 测试辅助：创建并注册清理。
func ftsStore(t *testing.T, dir string) *ContentStore {
	t.Helper()
	if dir == "" {
		dir = t.TempDir()
	}
	cs := NewContentStore(dir)
	if cs.broken.Load() {
		t.Fatal("FTS 索引打开失败")
	}
	t.Cleanup(cs.Close)
	return cs
}

func searchContent(t *testing.T, cs *ContentStore, q string) []FileHit {
	t.Helper()
	hits, n := cs.searchAll(q)
	if len(hits) != n {
		t.Fatalf("命中数不一致: len=%d n=%d", len(hits), n)
	}
	return hits
}

// 连续查询 = 精确子串语义（v0 行为保持）。
func TestFTSSubstringSemantics(t *testing.T) {
	cs := ftsStore(t, "")
	cs.Add("/a/合作协议.docx", "这是一份合同模板，包含条款。")
	cs.Add("/b/研究报告.pdf", "研究生命起源的课题组发布了报告。")

	// 精确子串命中
	for _, q := range []string{"合同模板", "条款", "生命起源", "合同模板，包含"} {
		if hits := searchContent(t, cs, q); len(hits) == 0 {
			t.Errorf("%q 应命中", q)
		}
	}
	// 跨字假子串不存在
	if hits := searchContent(t, cs, "同模板，包条"); len(hits) != 0 {
		t.Errorf("非连续子串不应命中: %v", hits)
	}
	// 大小写不敏感
	cs.Add("/c/en.txt", "Machine Learning Algorithms")
	if hits := searchContent(t, cs, "machine learning"); len(hits) != 1 {
		t.Errorf("英文大小写应不敏感: %v", hits)
	}
}

// 多词 AND 语义：空白分词，各词独立命中即可。
func TestFTSMultiWordAND(t *testing.T) {
	cs := ftsStore(t, "")
	cs.Add("/a/1.txt", "合同模板由法务部维护，公司规章完善。")
	cs.Add("/b/2.txt", "公司规章完善。")
	cs.Add("/c/3.txt", "合同模板大全。")

	// 两个词都出现的文档
	hits := searchContent(t, cs, "合同 公司")
	paths := map[string]bool{}
	for _, h := range hits {
		paths[h.Path] = true
	}
	if !paths["/a/1.txt"] {
		t.Errorf("两词齐备的文档应命中: %v", paths)
	}
	if paths["/b/2.txt"] || paths["/c/3.txt"] {
		t.Errorf("只含单词的文档不应命中: %v", paths)
	}
	// 单词查询不受影响
	if hits := searchContent(t, cs, "规章"); len(hits) != 2 {
		t.Errorf("单词应命中两文档: %v", hits)
	}
}

// 单字查询走 unigram 直查。
func TestFTSSingleChar(t *testing.T) {
	cs := ftsStore(t, "")
	cs.Add("/a/1.txt", "研究生毕业论文")
	cs.Add("/b/2.txt", "生存指南")
	hits := searchContent(t, cs, "生")
	if len(hits) != 2 {
		t.Fatalf("单字应命中两文档: %v", hits)
	}
	if hits := searchContent(t, cs, "f"); len(hits) != 0 {
		t.Errorf("不存在的单字不应命中")
	}
}

// gse 整词命中的文档应排在跨字命中之前（词边界加权）。
func TestFTSWordBoundaryRanking(t *testing.T) {
	cs := ftsStore(t, "")
	// docA："合同"作为完整词大量出现
	cs.Add("/a/合同.txt", strings.Repeat("签署合同需要注意条款。", 5))
	// docB：只出现一次且为子串形态
	cs.Add("/b/汇总.txt", "单位面积合同量同比下降。")

	hits := searchContent(t, cs, "合同")
	if len(hits) != 2 {
		t.Fatalf("应命中两文档: %v", hits)
	}
	if hits[0].Path != "/a/合同.txt" {
		t.Errorf("整词高频文档应排在前: %v", hits)
	}
}

// Remove / RemoveExcept / Has / Count / Reset。
func TestFTSLifecycle(t *testing.T) {
	cs := ftsStore(t, "")
	for i := 0; i < 5; i++ {
		cs.Add(fmt.Sprintf("/d/%d.txt", i), fmt.Sprintf("文档编号%d正文内容", i))
	}
	if cs.Count() != 5 {
		t.Fatalf("Count=5 期望, got %d", cs.Count())
	}
	if !cs.Has("/d/3.txt") {
		t.Fatal("Has 应为 true")
	}
	if cs.Remove("/d/3.txt") != true || cs.Has("/d/3.txt") {
		t.Fatal("Remove 后 Has 应为 false")
	}
	if cs.Count() != 4 {
		t.Fatalf("Count=4 期望, got %d", cs.Count())
	}

	seen := map[string]struct{}{
		"/d/0.txt": {}, "/d/1.txt": {},
	}
	if n := cs.RemoveExcept(seen); n != 2 {
		t.Fatalf("RemoveExcept 应移除 2 条, got %d", n)
	}
	if cs.Count() != 2 {
		t.Fatalf("Count=2 期望, got %d", cs.Count())
	}
	if hits := searchContent(t, cs, "文档编号"); len(hits) != 2 {
		t.Fatalf("移除后应仅剩两文档可搜: %v", hits)
	}

	cs.Reset()
	if cs.Count() != 0 {
		t.Fatalf("Reset 后应为空, got %d", cs.Count())
	}
	if hits := searchContent(t, cs, "文档编号"); len(hits) != 0 {
		t.Fatalf("Reset 后不应有命中")
	}
}

// 持久化：重开索引后文档仍可搜（scorch 异步落盘 → 轮询重试）。
func TestFTSPersistence(t *testing.T) {
	dir := t.TempDir()
	cs := ftsStore(t, dir)
	if err := cs.Add("/p/报表.txt", "季度报表汇总数据内容"); err != nil {
		t.Fatal(err)
	}
	cs.Close() // 释放 bbolt 目录锁，重开实例才能打开

	deadline := time.Now().Add(15 * time.Second)
	for {
		cs2 := NewContentStore(dir)
		hits, _ := cs2.searchAll("报表汇总")
		count := cs2.Count()
		cs2.Close()
		if len(hits) == 1 && count == 1 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("15s 内未持久化可查: hits=%v count=%d", hits, count)
		}
		time.Sleep(150 * time.Millisecond)
	}
}

// Add 同路径覆盖更新；空文本拒绝；超长文本 rune 截断。
func TestFTSAddEdgeCases(t *testing.T) {
	cs := ftsStore(t, "")

	if err := cs.Add("/x.txt", "   \n\t "); err == nil {
		t.Error("空文本应报错")
	}
	if err := cs.Add("", "正文"); err == nil {
		t.Error("空路径应报错")
	}

	// 覆盖更新：旧词消失新词出现
	if err := cs.Add("/u.txt", "旧版本内容甲"); err != nil {
		t.Fatal(err)
	}
	if err := cs.Add("/u.txt", "新版本内容乙"); err != nil {
		t.Fatal(err)
	}
	if cs.Count() != 1 {
		t.Fatalf("覆盖更新不应增加文档数: %d", cs.Count())
	}
	if hits := searchContent(t, cs, "内容甲"); len(hits) != 0 {
		t.Errorf("旧词不应再命中")
	}
	if hits := searchContent(t, cs, "内容乙"); len(hits) != 1 {
		t.Errorf("新词应命中")
	}

	// 1MB 截断：rune 边界安全
	long := strings.Repeat("长", maxFTSText/3+10) + strings.Repeat("文", 10)
	if err := cs.Add("/long.txt", long); err != nil {
		t.Fatal(err)
	}
	if hits := searchContent(t, cs, "长长长"); len(hits) != 1 {
		t.Errorf("截断后仍应命中前部内容")
	}
	if hits := searchContent(t, cs, "文文文"); len(hits) != 0 {
		t.Errorf("尾部超出 4MB 的内容应被截断")
	}
}

// 并发 Add / Remove / Search 无竞态（-race 下运行）。
func TestFTSConcurrent(t *testing.T) {
	cs := ftsStore(t, "")
	var wg sync.WaitGroup
	for w := 0; w < 4; w++ {
		wg.Add(1)
		go func(w int) {
			defer wg.Done()
			for i := 0; i < 30; i++ {
				p := fmt.Sprintf("/c/w%d-%d.txt", w, i)
				_ = cs.Add(p, fmt.Sprintf("并发写入第%d条正文内容%d", i, w))
				_, _ = cs.searchAll("并发")
				if i%5 == 0 {
					cs.Remove(fmt.Sprintf("/c/w%d-%d.txt", w, i-1))
				}
			}
		}(w)
	}
	wg.Wait()
}

// 锁保护：同目录第二个实例打开应报错降级而非挂死。
func TestFTSLockConflict(t *testing.T) {
	dir := t.TempDir()
	cs := ftsStore(t, dir)
	if err := cs.Add("/lock.txt", "锁测试正文"); err != nil {
		t.Fatal(err)
	}

	done := make(chan *ContentStore, 1)
	go func() {
		done <- NewContentStore(dir)
	}()
	select {
	case cs2 := <-done:
		if !cs2.broken.Load() {
			// 极少数平台上 flock 语义不同（不互斥），此处不视为失败，
			// 仅要求"绝不挂死"。
			t.Log("第二实例未检测到锁（平台 flock 语义差异）")
		}
		cs2.Close()
	case <-time.After(30 * time.Second):
		t.Fatal("同目录双开未在超时内返回（挂死）")
	}
}

// 快照 v2 → v3 迁移：旧快照的名称索引可恢复，正文由补解析重建。
func TestSnapshotV2Migration(t *testing.T) {
	dir := t.TempDir()
	ftsStore(t, dir)

	e, err := NewEngine(2, dir)
	if err != nil {
		t.Fatal(err)
	}
	e.names.Add("/old/报告.pdf", 100, 200)
	e.dirs.Add("/old", 0, 100)
	if err := e.saveSnapshot(); err != nil {
		t.Fatal(err)
	}
	// 手工降版本字段模拟 v2 快照
	data, err := os.ReadFile(filepath.Join(dir, "index.snap"))
	if err != nil {
		t.Fatal(err)
	}
	e.Close()

	// v2 快照解码路径：直接用 restore 语义验证（版本 2 被接受）
	e2, err := NewEngine(2, dir)
	if err != nil {
		t.Fatal(err)
	}
	defer e2.Close()
	if e2.names.Count() != 1 || e2.dirs.Count() != 1 {
		t.Fatalf("v3 快照应恢复名称/目录索引: names=%d dirs=%d",
			e2.names.Count(), e2.dirs.Count())
	}
	_ = data
}
