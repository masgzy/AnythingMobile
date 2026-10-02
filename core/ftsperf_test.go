package core

// alpha16 搜索性能改造的行为测试：
//   - 验证预算（verifyCap）截断语义与 total 的精确/下界两种口径；
//   - 预评分排序截断保留高分文档；
//   - AddBatch 与逐条 Add 的等价性（含空文本/重复路径边界）；
//   - Engine 搜索缓存：命中、突变失效。

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// addDocs 批量写入测试文档（走 Add，构造阶段无需攒批）。
func addDocs(t *testing.T, cs *ContentStore, texts map[string]string) {
	t.Helper()
	for p, txt := range texts {
		if err := cs.Add(p, txt); err != nil {
			t.Fatalf("Add %s: %v", p, err)
		}
	}
}

// ≤2 字词：候选集即精确命中 —— 验证预算截断命中列表，但 total 恒精确。
func TestFTSSearchCapKeepsExactTotalForShortTerms(t *testing.T) {
	cs := ftsStore(t, "")
	texts := map[string]string{}
	for i := 0; i < 300; i++ {
		texts[fmt.Sprintf("/doc/%03d.txt", i)] = "年度会议纪要正文示例"
	}
	addDocs(t, cs, texts)

	hits, total := cs.searchAll("会议", 50)
	if len(hits) != 50 {
		t.Fatalf("预算截断后应返回 50 条，实际 %d", len(hits))
	}
	if total != 300 {
		t.Fatalf("≤2 字词 total 应精确为 300（不受截断影响），实际 %d", total)
	}

	// 不设预算：全量返回
	hits, total = cs.searchAll("会议", 0)
	if len(hits) != 300 || total != 300 {
		t.Fatalf("无预算应全量返回 300，实际 hits=%d total=%d", len(hits), total)
	}
}

// ≥3 字词：候选存在跨 bigram 假阳性 —— 候选超预算时 total 为已验证数（下界），
// 不超预算时全量验证保持精确；假阳性在任何预算下都必须被剔除。
func TestFTSSearchLongWordVerification(t *testing.T) {
	cs := ftsStore(t, "")
	texts := map[string]string{}
	for i := 0; i < 250; i++ {
		texts[fmt.Sprintf("/doc/%03d.txt", i)] = "本次会议纪要已经整理完毕"
	}
	// 假阳性样本：bigram（会议/议纪/纪要）全部出现但互不相邻
	texts["/doc/false.txt"] = "会议结束。纪要待发"
	addDocs(t, cs, texts)

	// 无预算：全量验证，假阳性剔除，total 精确
	hits, total := cs.searchAll("会议纪要", 0)
	if total != 250 {
		t.Fatalf("全量验证 total 应为 250（假阳性剔除），实际 %d", total)
	}
	for _, h := range hits {
		if strings.Contains(h.Path, "false") {
			t.Fatalf("假阳性文档不应命中: %s", h.Path)
		}
	}

	// 候选(251) > 预算(10)：走下界路径，total = 已验证数 = 10
	hits, total = cs.searchAll("会议纪要", 10)
	if len(hits) != 10 || total != 10 {
		t.Fatalf("预算 10 应返回 10 条且 total 为下界 10，实际 hits=%d total=%d", len(hits), total)
	}
	for _, h := range hits {
		if strings.Contains(h.Path, "false") {
			t.Fatalf("下界路径同样必须剔除假阳性: %s", h.Path)
		}
	}
}

// 预评分排序：预算截断保留的应是高分（词频×整词加权）文档。
func TestFTSSearchCapPicksTopScored(t *testing.T) {
	cs := ftsStore(t, "")
	texts := map[string]string{
		"/doc/low.txt":  "合同首",
		"/doc/high.txt": "合同 合同 合同 合同 合同 合同 合同 合同 合同 合同",
	}
	for i := 0; i < 30; i++ {
		texts[fmt.Sprintf("/doc/mid%02d.txt", i)] = "合同正文若干内容"
	}
	addDocs(t, cs, texts)

	hits, _ := cs.searchAll("合同", 5)
	if len(hits) == 0 {
		t.Fatal("应有命中")
	}
	if hits[0].Path != "/doc/high.txt" {
		t.Fatalf("截断保留的应是最高分文档，首位 = %s", hits[0].Path)
	}
}

// AddBatch 与逐条 Add 等价：空文本跳过、重复路径保尾、可搜性一致。
func TestContentStoreAddBatch(t *testing.T) {
	cs := ftsStore(t, "")
	docs := []DocText{
		{Path: "/doc/a.txt", Text: "项目合同要点"},
		{Path: "/doc/b.txt", Text: "会议纪要汇总"},
		{Path: "/doc/empty.txt", Text: "   "}, // 空文本：跳过
		{Path: "/doc/a.txt", Text: "项目合同最终版"}, // 重复路径：保尾覆盖
	}
	n := cs.AddBatch(docs)
	if n != 2 {
		t.Fatalf("AddBatch 应成功 2 条（空文本跳过、重复路径去重保尾），实际 %d", n)
	}
	if cs.Count() != 2 {
		t.Fatalf("Count 应为 2，实际 %d", cs.Count())
	}
	// 保尾覆盖：首版“要点”不再命中，末版“最终版”命中
	if _, total := cs.searchAll("最终版", 0); total != 1 {
		t.Fatalf("重复路径应保留末版文本，total=%d", total)
	}
	if _, total := cs.searchAll("要点", 0); total != 0 {
		t.Fatalf("重复路径首版应被覆盖，total=%d", total)
	}
	hits, total := cs.searchAll("合同", 0)
	if total != 1 || len(hits) != 1 || hits[0].Path != "/doc/a.txt" {
		t.Fatalf("覆盖后 /doc/a.txt 应命中且仅一条，实际 total=%d", total)
	}
	if _, total := cs.searchAll("纪要", 0); total != 1 {
		t.Fatalf("/doc/b.txt 应可搜，total=%d", total)
	}
	if cs.Has("/doc/empty.txt") {
		t.Fatal("空文本文档不应入库")
	}
}

// 搜索缓存：同查询命中缓存；索引突变（RemovePaths/扫描收尾）后失效。
func TestEngineSearchCacheInvalidation(t *testing.T) {
	dir := t.TempDir()
	e, err := NewEngine(2, dir)
	if err != nil {
		t.Fatalf("NewEngine: %v", err)
	}
	t.Cleanup(e.Close)
	defer e.saveWG.Wait()

	// 两份文件（.txt 不参与全文解析）：先扫描收编文件名，再经宿主通道
	// 注入正文（full 扫描会 Reset 全文库，正文必须在扫描之后注入）
	for name, text := range map[string]string{
		"a.txt": "缓存测试文档甲",
		"b.txt": "缓存测试文档乙",
	} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(text), 0o644); err != nil {
			t.Fatalf("写文件: %v", err)
		}
	}
	e.StartScan(fmt.Sprintf(`{"roots":[%q],"mode":"full"}`, dir))
	e.saveWG.Wait()
	for name, text := range map[string]string{
		"a.txt": "缓存测试文档甲",
		"b.txt": "缓存测试文档乙",
	} {
		p := filepath.Join(dir, name)
		if err := e.AddDocumentText(p, text); err != nil {
			t.Fatalf("AddDocumentText %s: %v", p, err)
		}
	}

	r1, err := e.Search("缓存", 300)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	r2, err := e.Search("缓存", 300)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if r1 != r2 {
		t.Fatal("同一查询短时间内应命中缓存（响应一致）")
	}
	var sr SearchResponse
	if err := json.Unmarshal([]byte(r2), &sr); err != nil {
		t.Fatalf("响应解析: %v", err)
	}
	if sr.TotalContent != 2 {
		t.Fatalf("应命中 2 个文档，实际 %d", sr.TotalContent)
	}

	// 删除一个文件并 RemovePaths 同步 → 缓存必须失效，计数实时反映
	if err := os.Remove(filepath.Join(dir, "a.txt")); err != nil {
		t.Fatalf("删除失败: %v", err)
	}
	if _, err := e.RemovePaths(fmt.Sprintf("[%q]", filepath.Join(dir, "a.txt"))); err != nil {
		t.Fatalf("RemovePaths: %v", err)
	}
	r3, err := e.Search("缓存", 300)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	var sr3 SearchResponse
	if err := json.Unmarshal([]byte(r3), &sr3); err != nil {
		t.Fatalf("响应解析: %v", err)
	}
	if sr3.TotalContent != 1 {
		t.Fatalf("RemovePaths 后缓存应失效并反映新计数（期望 1，实际 %d）", sr3.TotalContent)
	}
}

// BenchmarkFTSSearchHighFreq 高频词基准：2000 文档查单字，验证预算
// 截断后的查询路径耗时（CI 可用 -bench 跟踪回归）。
func BenchmarkFTSSearchHighFreq(b *testing.B) {
	cs := NewContentStore("")
	if cs.broken.Load() {
		b.Fatal("FTS 打开失败")
	}
	defer cs.Close()
	batch := make([]DocText, 0, 2000)
	for i := 0; i < 2000; i++ {
		batch = append(batch, DocText{
			Path: fmt.Sprintf("/doc/%04d.txt", i),
			Text: fmt.Sprintf("项目年度会议材料第%d份，会议纪要归档", i),
		})
	}
	cs.AddBatch(batch)

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		if _, total := cs.searchAll("会议", 300); total != 2000 {
			b.Fatalf("total 应精确 2000，实际 %d", total)
		}
	}
}
