package core

// M4 搜索语法：type: / dir: 过滤器。
//
// 语法形式：
//   type:video 音乐          —— 视频类文件名或正文含"音乐"
//   dir:Download 合同        —— 路径位于名称含 "download" 的目录下
//   dir:"My Documents" 报告  —— 引号包裹含空格的目录名
//   type:doc 预算 dir:工作   —— 过滤器可组合（多 type 取 OR，多 dir 取 AND）
//
// 语义要点：
//   - type: 对目录命中（kind=folder）不生效 —— 目录没有扩展名，
//     type 过滤后目录命中一律剔除；
//   - dir: 匹配"父目录路径"的包含关系（大小写不敏感），目录条目
//     用其自身路径的父目录参与匹配；
//   - 过滤后剩余关键词为空但存在过滤器时，视为非法组合（浏览某类
//     文件请用界面底部的类别快筛，查询语法服务"缩小搜索范围"）。

import (
	"fmt"
	"path/filepath"
	"sort"
	"strings"
)

// queryFilter 解析结果。
type queryFilter struct {
	Terms []string // 剩余普通词（保序）
	Types []string // type: 过滤（已归一化为英文标识，保序去重）
	Dirs  []string // dir: 过滤（保留原文大小写，匹配时不敏感）
}

// String 供调试与 UI 提示。
func (f queryFilter) String() string {
	return fmt.Sprintf("terms=%q types=%q dirs=%q", f.Terms, f.Types, f.Dirs)
}

// typeAliases 过滤词归一化（中英文别名 → 内部标识）。
var typeAliases = map[string]string{
	"video": "video", "视频": "video", "影片": "video", "movie": "video", "movies": "video",
	"music": "music", "音乐": "music", "audio": "music", "音频": "music",
	"image": "image", "图片": "image", "photo": "image", "photos": "image", "照片": "image", "pic": "image",
	"doc": "doc", "docs": "doc", "document": "doc", "documents": "doc", "文档": "doc",
	"file": "file", "files": "file", "文件": "file",
	"apk": "apk", "安装包": "apk",
}

// extTypeMap 扩展名 → 类型标识。与宿主 UI 的类别快筛保持同源语义。
var extTypeMap = map[string]string{}

func init() {
	byType := map[string][]string{
		"video": {".mp4", ".avi", ".mkv", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".3gp", ".3g2", ".mpg", ".mpeg", ".ts", ".vob", ".ogv", ".rm", ".rmvb"},
		"music": {".mp3", ".flac", ".wav", ".aac", ".ogg", ".oga", ".m4a", ".wma", ".opus", ".ape", ".amr", ".mid", ".midi"},
		"image": {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".heif", ".tiff", ".tif", ".svg", ".ico", ".dng", ".raw", ".cr2", ".nef", ".arw"},
		"doc":   {".docx", ".doc", ".pptx", ".ppt", ".xlsx", ".xls", ".wps", ".pdf", ".txt", ".md", ".rtf", ".csv", ".epub", ".mobi", ".azw3", ".djvu"},
		"apk":   {".apk", ".apkm", ".xapk"},
	}
	for t, exts := range byType {
		for _, e := range exts {
			extTypeMap[e] = t
		}
	}
}

// parseQuery 拆出 type:/dir: 过滤器与剩余关键词。
// 无任何语法 token 时 Terms 为 [原文] 的等价形式（空白分词），
// 行为与 v0 完全一致。
func parseQuery(raw string) queryFilter {
	var f queryFilter
	seenType := map[string]bool{}

	i := 0
	rs := []rune(raw)
	for i < len(rs) {
		// 跳过空白
		for i < len(rs) && isSpaceRune(rs[i]) {
			i++
		}
		if i >= len(rs) {
			break
		}
		rest := string(rs[i:])
		lower := strings.ToLower(rest)

		switch {
		case strings.HasPrefix(lower, "type:"):
			i += len("type:")
			val, next := readFilterValue(string(rs[i:]))
			i += next
			if t, ok := typeAliases[strings.ToLower(val)]; ok && !seenType[t] {
				seenType[t] = true
				f.Types = append(f.Types, t)
			}
		case strings.HasPrefix(lower, "dir:"):
			i += len("dir:")
			val, next := readFilterValue(string(rs[i:]))
			i += next
			if val != "" {
				f.Dirs = append(f.Dirs, val)
			}
		default:
			// 普通词：读到下一个空白（引号词支持空格）
			if rs[i] == '"' {
				i++
				start := i
				for i < len(rs) && rs[i] != '"' {
					i++
				}
				f.Terms = append(f.Terms, string(rs[start:i]))
				if i < len(rs) {
					i++ // 消耗收尾引号
				}
			} else {
				start := i
				for i < len(rs) && !isSpaceRune(rs[i]) {
					i++
				}
				f.Terms = append(f.Terms, string(rs[start:i]))
			}
		}
	}

	if len(f.Terms) == 0 && len(f.Types) == 0 && len(f.Dirs) == 0 {
		// 兼容防御：全空白输入
		return f
	}
	return f
}

// readFilterValue 读取过滤器的值：支持 "带空格的引号值" 或连续非空白串。
// 返回值与消费的 rune 数。
func readFilterValue(s string) (val string, consumed int) {
	rs := []rune(s)
	if len(rs) == 0 {
		return "", 0
	}
	if rs[0] == '"' {
		for j := 1; j < len(rs); j++ {
			if rs[j] == '"' {
				return string(rs[1:j]), j + 1
			}
		}
		// 未闭合引号：取剩余全部
		return string(rs[1:]), len(rs)
	}
	j := 0
	for j < len(rs) && !isSpaceRune(rs[j]) {
		j++
	}
	return string(rs[:j]), j
}

// joinedTerms 剩余关键词重新拼为查询串（空格连接）。
func (f queryFilter) joinedTerms() string {
	return strings.Join(f.Terms, " ")
}

// filterHits 对一组命中应用 type/dir 过滤。
func filterHits(hits []FileHit, f queryFilter) []FileHit {
	if len(f.Types) == 0 && len(f.Dirs) == 0 {
		return hits
	}
	out := hits[:0:0]
	for _, h := range hits {
		if !matchTypes(h, f.Types) {
			continue
		}
		if !matchDirs(h.Path, f.Dirs) {
			continue
		}
		out = append(out, h)
	}
	return out
}

// matchTypes 类型过滤：目录条目不匹配任何 type。
func matchTypes(h FileHit, types []string) bool {
	if len(types) == 0 {
		return true
	}
	if h.Kind == "folder" {
		return false
	}
	ext := extOf(h.Path)
	t := extTypeMap[ext]
	if t == "" {
		t = "file" // 未知扩展名归入通用文件
	}
	for _, want := range types {
		if want == "file" || want == t {
			return true
		}
	}
	return false
}

// matchDirs 目录过滤：路径的父目录需包含全部 dir 串（AND，大小写不敏感）。
// 目录条目以自身路径的父目录参与匹配。
func matchDirs(path string, dirs []string) bool {
	if len(dirs) == 0 {
		return true
	}
	parent := filepath.Dir(path)
	parentLower := strings.ToLower(parent)
	for _, d := range dirs {
		if !strings.Contains(parentLower, strings.ToLower(d)) {
			return false
		}
	}
	return true
}

// sortStrings 去重辅助（供测试）。
func sortStrings(in []string) []string {
	out := append([]string(nil), in...)
	sort.Strings(out)
	return out
}

func isSpaceRune(r rune) bool {
	switch r {
	case ' ', '\t', '\n', '\r', '\v', '\f', 0x85, 0xA0:
		return true
	}
	return false
}
