package core

// M4 搜索语法（type:/dir:）解析与过滤测试。

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestParseQueryNoSyntax(t *testing.T) {
	f := parseQuery("会议记录")
	if strings.Join(f.Terms, " ") != "会议记录" || len(f.Types) != 0 || len(f.Dirs) != 0 {
		t.Fatalf("无语法应原样保留: %v", f)
	}
}

func TestParseQueryType(t *testing.T) {
	f := parseQuery("type:video 音乐现场")
	if strings.Join(f.Terms, " ") != "音乐现场" {
		t.Fatalf("剩余词错误: %v", f.Terms)
	}
	if len(f.Types) != 1 || f.Types[0] != "video" {
		t.Fatalf("type 归一化错误: %v", f.Types)
	}

	// 中文别名 + 多 type（OR）
	f = parseQuery("type:视频 type:图片 封面")
	if len(f.Types) != 2 || f.Types[0] != "video" || f.Types[1] != "image" {
		t.Fatalf("中文别名/多 type 错误: %v", f.Types)
	}

	// 未知类型：丢弃不生效
	f = parseQuery("type:foo 关键词")
	if len(f.Types) != 0 || strings.Join(f.Terms, " ") != "关键词" {
		t.Fatalf("未知 type 应被丢弃: %v", f)
	}
}

func TestParseQueryDir(t *testing.T) {
	f := parseQuery(`dir:Download 合同`)
	if len(f.Dirs) != 1 || f.Dirs[0] != "Download" {
		t.Fatalf("dir 解析错误: %v", f)
	}
	if strings.Join(f.Terms, " ") != "合同" {
		t.Fatalf("剩余词错误: %v", f.Terms)
	}

	// 引号含空格
	f = parseQuery(`dir:"My Documents" 报告`)
	if len(f.Dirs) != 1 || f.Dirs[0] != "My Documents" {
		t.Fatalf("引号 dir 解析错误: %v", f)
	}
}

func TestParseQueryMixed(t *testing.T) {
	f := parseQuery(`type:doc dir:工作 预算`)
	if len(f.Types) != 1 || len(f.Dirs) != 1 || strings.Join(f.Terms, " ") != "预算" {
		t.Fatalf("混合解析错误: %v", f)
	}
}

func TestFilterHitsTypes(t *testing.T) {
	hits := []FileHit{
		{Path: "/sdcard/a.mp4", Kind: "file"},
		{Path: "/sdcard/b.jpg", Kind: "file"},
		{Path: "/sdcard/c.docx", Kind: "file"},
		{Path: "/sdcard/d.txt", Kind: "file"},
		{Path: "/sdcard/e.apk", Kind: "file"},
		{Path: "/sdcard/DCIM", Kind: "folder"},
	}

	// type:video 只留视频
	out := filterHits(hits, parseQuery("type:video x"))
	if len(out) != 1 || out[0].Path != "/sdcard/a.mp4" {
		t.Fatalf("type:video 过滤错误: %v", out)
	}

	// 多 type OR：video+image
	out = filterHits(hits, parseQuery("type:video type:图片 x"))
	if len(out) != 2 {
		t.Fatalf("多 type OR 错误: %v", out)
	}

	// type:file 含未知扩展名与全部文件、排除目录
	out = filterHits(hits, parseQuery("type:file x"))
	if len(out) != 5 {
		t.Fatalf("type:file 应含全部文件排除目录: %v", out)
	}

	// 目录在任何 type 过滤下被剔除
	out = filterHits(hits, parseQuery("type:apk x"))
	if len(out) != 1 || out[0].Path != "/sdcard/e.apk" {
		t.Fatalf("type:apk 过滤错误: %v", out)
	}
}

func TestFilterHitsDirs(t *testing.T) {
	hits := []FileHit{
		{Path: "/sdcard/Download/x.mp4", Kind: "file"},
		{Path: "/sdcard/DCIM/Camera/y.jpg", Kind: "file"},
		{Path: "/sdcard/Download/sub/z.txt", Kind: "file"},
		{Path: "/sdcard/DCIM", Kind: "folder"},
	}

	// dir:Download 命中父目录含 download 的条目
	out := filterHits(hits, parseQuery("dir:Download x"))
	if len(out) != 2 {
		t.Fatalf("dir 过滤错误: %v", out)
	}

	// 多 dir AND
	out = filterHits(hits, parseQuery(`dir:DCIM dir:camera x`))
	if len(out) != 1 || out[0].Path != "/sdcard/DCIM/Camera/y.jpg" {
		t.Fatalf("多 dir AND 错误: %v", out)
	}
}

// 引擎级 M4 搜索：语法剥离 + 过滤 + 真实计数。
func TestEngineSearchSyntax(t *testing.T) {
	e, err := NewEngine(2, "")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(e.Close)

	// 文件名命中
	for i, name := range []string{"旅行视频.mp4", "会议记录.txt", "风景图片.jpg", "游戏.apk"} {
		e.names.Add("/sdcard/"+name, int64(i+1), 100)
	}
	e.dirs.Add("/sdcard/Download", 0, 100)
	// 全文命中
	if err := e.content.Add("/sdcard/笔记.txt", "关于机器学习的旅行视频推荐"); err != nil {
		t.Fatal(err)
	}
	e.enrichContentHits(nil) // 冒烟：空列表不出错

	// type:video —— 文件名与全文各一条 .mp4？全文命中 path 是 .txt → 被剔除
	resp, err := e.Search("type:video 视频", 50)
	if err != nil {
		t.Fatal(err)
	}
	var sr SearchResponse
	if err := json.Unmarshal([]byte(resp), &sr); err != nil {
		t.Fatal(err)
	}
	if sr.TotalFiles != 1 {
		t.Fatalf("type:video 文件名计数应为 1: %d", sr.TotalFiles)
	}
	if sr.TotalContent != 0 {
		t.Fatalf("type:video 下 .txt 全文命中应被剔除: %d", sr.TotalContent)
	}
	if sr.Hits[0].Path != "/sdcard/旅行视频.mp4" {
		t.Fatalf("命中错误: %+v", sr.Hits)
	}
	if sr.Hits[0].Size == 0 {
		t.Fatalf("全文命中补全元数据失败: %+v", sr.Hits[0])
	}

	// dir:Download 仅命中目录页签（该目录自身父路径 /sdcard 不含 download → 0）
	// 添加一个位于 Download 下的文件再验证
	e.names.Add("/sdcard/Download/安装包.apk", 5, 100)
	resp, err = e.Search("dir:download 安装", 50)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal([]byte(resp), &sr); err != nil {
		t.Fatal(err)
	}
	if sr.TotalFiles != 1 || sr.Hits[0].Path != "/sdcard/Download/安装包.apk" {
		t.Fatalf("dir:download 过滤失败: %+v", sr)
	}

	// 过滤器无关键词 → 报错
	if _, err := e.Search("type:video", 50); err == nil {
		t.Fatal("type: 无关键词应报错")
	}

	// 无语法行为不变
	resp, err = e.Search("会议记录", 50)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal([]byte(resp), &sr); err != nil {
		t.Fatal(err)
	}
	if sr.TotalFiles != 1 || sr.Hits[0].Path != "/sdcard/会议记录.txt" {
		t.Fatalf("无语法行为变化: %+v", sr)
	}
}
