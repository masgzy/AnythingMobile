package core

import (
        "path/filepath"
        "strings"
        "testing"
)

func TestNameIndexAddAndSearch(t *testing.T) {
        idx := NewNameIndex("file")
        idx.Add("/sdcard/Documents/年度报告2024.docx", 1024, 1700000000000)
        idx.Add("/sdcard/DCIM/图片.jpg", 20480, 1700000100000)
        idx.Add("/sdcard/Download/report_final.pdf", 512, 1700000200000)

        // 中文 bigram 命中
        hits := idx.Search("报告", 10)
        if len(hits) != 1 || !strings.HasSuffix(hits[0].Path, "年度报告2024.docx") {
                t.Fatalf("中文子串查询失败: %+v", hits)
        }
        // 英文大小写折叠
        hits = idx.Search("REPORT", 10)
        if len(hits) != 1 || !strings.HasSuffix(hits[0].Path, "report_final.pdf") {
                t.Fatalf("大小写折叠查询失败: %+v", hits)
        }
        // 单字符线性扫描路径
        hits = idx.Search("片", 10)
        if len(hits) != 1 || !strings.HasSuffix(hits[0].Path, "图片.jpg") {
                t.Fatalf("单字符查询失败: %+v", hits)
        }
        // 无命中
        if hits := idx.Search("不存在的文件", 10); len(hits) != 0 {
                t.Fatalf("不应命中: %+v", hits)
        }
        // limit 生效
        for i := 0; i < 20; i++ {
                idx.Add(filepath.Join("/sdcard/a", string(rune('a'+i))+"报告.txt"), 1, 1)
        }
        if hits := idx.Search("报告", 5); len(hits) != 5 {
                t.Fatalf("limit 未生效: got %d", len(hits))
        }
}

func TestNameIndexDedup(t *testing.T) {
        idx := NewNameIndex("file")
        idx.Add("/sdcard/a.txt", 1, 1)
        idx.Add("/sdcard/a.txt", 2, 2) // 同路径更新
        if idx.Count() != 1 {
                t.Fatalf("去重失败: count=%d", idx.Count())
        }
        hits := idx.Search("a.txt", 10)
        if len(hits) != 1 || hits[0].Size != 2 {
                t.Fatalf("更新未生效: %+v", hits)
        }
}

func TestNameIndexIncrementalAPI(t *testing.T) {
        idx := NewNameIndex("file")
        idx.Add("/sdcard/a.txt", 1, 1)
        idx.Add("/sdcard/b.txt", 2, 2)
        idx.Add("/sdcard/c.txt", 3, 3)

        // LookupPath 命中
        size, mtime, ok := idx.LookupPath("/sdcard/a.txt")
        if !ok || size != 1 || mtime != 1 {
                t.Fatalf("LookupPath 失败: %d %d %v", size, mtime, ok)
        }
        if _, _, ok := idx.LookupPath("/sdcard/missing"); ok {
                t.Fatal("不存在的路径不应命中")
        }

        // RemovePath
        if !idx.RemovePath("/sdcard/b.txt") || idx.Count() != 2 {
                t.Fatalf("RemovePath 失败: count=%d", idx.Count())
        }
        if hits := idx.Search("b.txt", 10); len(hits) != 0 {
                t.Fatalf("移除后不应命中: %+v", hits)
        }

        // RemoveExcept：仅保留 a.txt
        removed := idx.RemoveExcept(map[string]struct{}{"/sdcard/a.txt": {}})
        if len(removed) != 1 || removed[0] != "/sdcard/c.txt" || idx.Count() != 1 {
                t.Fatalf("RemoveExcept 失败: removed=%v count=%d", removed, idx.Count())
        }

        // Reset
        idx.Reset()
        if idx.Count() != 0 {
                t.Fatalf("Reset 失败: count=%d", idx.Count())
        }
}

func TestNameIndexKind(t *testing.T) {
        dirs := NewNameIndex("folder")
        dirs.Add("/sdcard/DCIM", 0, 1)
        hits := dirs.Search("DCIM", 10)
        if len(hits) != 1 || hits[0].Kind != "folder" {
                t.Fatalf("kind 标记失败: %+v", hits)
        }
}
