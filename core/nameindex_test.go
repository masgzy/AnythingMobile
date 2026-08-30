package core

import (
        "path/filepath"
        "strings"
        "testing"
)

func TestNameIndexAddAndSearch(t *testing.T) {
        idx := NewNameIndex()
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
        idx := NewNameIndex()
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
