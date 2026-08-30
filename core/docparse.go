package core

import (
	"archive/zip"
	"encoding/xml"
	"errors"
	"io"
	"sort"
	"strconv"
	"strings"
)

// ErrUnsupportedFormat 暂不支持该格式的正文抽取。
var ErrUnsupportedFormat = errors.New("core: 暂不支持该格式的正文抽取")

// maxExtractText 单文档抽取文本的上限（1MB），超出截断。
const maxExtractText = 1 << 20

// ExtractText 抽取 OOXML 家族（docx/pptx/xlsx）文档的纯文本。
// 仅依赖标准库（zip + encoding/xml），避免任何 GPL/AGPL 依赖。
// .doc/.ppt/.xls 由宿主 ExternalParser 兜底；.pdf 在 M3 里程碑接入。
func ExtractText(path string) (string, error) {
	switch strings.ToLower(pathExt(path)) {
	case ".docx":
		return extractEntries(path, nil, exactFilter("word/document.xml"), "t", map[string]bool{"p": true})
	case ".pptx":
		return extractPptx(path)
	case ".xlsx":
		return extractXlsx(path)
	default:
		return "", ErrUnsupportedFormat
	}
}

// entryFilter 判断 zip 条目是否参与抽取。
type entryFilter func(name string) bool

func exactFilter(want string) entryFilter {
	return func(name string) bool { return name == want }
}

func prefixFilter(prefix string) entryFilter {
	return func(name string) bool {
		return strings.HasPrefix(name, prefix) && strings.HasSuffix(name, ".xml")
	}
}

// extractEntries 按过滤器抽取 zip 内若干 XML 的文本。
func extractEntries(path string, order []string, keep entryFilter, tag string, paraTags map[string]bool) (string, error) {
	zr, err := zip.OpenReader(path)
	if err != nil {
		return "", err
	}
	defer zr.Close()

	var names []string
	for _, f := range zr.File {
		if keep(f.Name) {
			names = append(names, f.Name)
		}
	}
	if order == nil {
		sort.Strings(names)
	} else {
		names = sortIn(names, order)
	}

	var sb strings.Builder
	for _, name := range names {
		var f *zip.File
		for _, cand := range zr.File {
			if cand.Name == name {
				f = cand
				break
			}
		}
		if f == nil {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			continue
		}
		text, _ := captureXML(rc, tag, paraTags)
		rc.Close()
		if strings.TrimSpace(text) != "" {
			if sb.Len() > 0 {
				sb.WriteByte('\n')
			}
			sb.WriteString(text)
		}
		if sb.Len() >= maxExtractText {
			break
		}
	}
	return truncate(&sb), nil
}

// extractPptx 幻灯片与备注页按编号顺序抽取 <a:t> 文本。
func extractPptx(path string) (string, error) {
	zr, err := zip.OpenReader(path)
	if err != nil {
		return "", err
	}
	defer zr.Close()

	var slides []string
	for _, f := range zr.File {
		if (strings.HasPrefix(f.Name, "ppt/slides/slide") ||
			strings.HasPrefix(f.Name, "ppt/notesSlides/notesSlide")) &&
			strings.HasSuffix(f.Name, ".xml") {
			slides = append(slides, f.Name)
		}
	}
	sort.Slice(slides, func(i, j int) bool { return slideNum(slides[i]) < slideNum(slides[j]) })

	var sb strings.Builder
	for _, name := range slides {
		var f *zip.File
		for _, cand := range zr.File {
			if cand.Name == name {
				f = cand
				break
			}
		}
		if f == nil {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			continue
		}
		text, _ := captureXML(rc, "t", map[string]bool{"p": true})
		rc.Close()
		if strings.TrimSpace(text) != "" {
			if sb.Len() > 0 {
				sb.WriteByte('\n')
			}
			sb.WriteString(text)
		}
		if sb.Len() >= maxExtractText {
			break
		}
	}
	return truncate(&sb), nil
}

// extractXlsx 抽取共享字符串表与工作表内联字符串。
func extractXlsx(path string) (string, error) {
	zr, err := zip.OpenReader(path)
	if err != nil {
		return "", err
	}
	defer zr.Close()

	var sb strings.Builder
	for _, f := range zr.File {
		isShared := f.Name == "xl/sharedStrings.xml"
		isSheet := strings.HasPrefix(f.Name, "xl/worksheets/") && strings.HasSuffix(f.Name, ".xml")
		if !isShared && !isSheet {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			continue
		}
		text, _ := captureXML(rc, "t", map[string]bool{"row": true})
		rc.Close()
		if sb.Len() > 0 {
			sb.WriteByte('\n')
		}
		sb.WriteString(text)
		if sb.Len() >= maxExtractText {
			break
		}
	}
	return truncate(&sb), nil
}

// captureXML 流式解析 XML，收集名为 tag 的元素的字符数据；
// 结束标签命中 paraTags 时插入换行。损坏 XML 返回已收集内容（容错优先）。
func captureXML(r io.Reader, tag string, paraTags map[string]bool) (string, error) {
	var sb strings.Builder
	dec := xml.NewDecoder(r)
	dec.Strict = false
	inTag := false
	for {
		tok, err := dec.Token()
		if err != nil {
			if err == io.EOF {
				break
			}
			return sb.String(), nil
		}
		switch t := tok.(type) {
		case xml.StartElement:
			if t.Name.Local == tag {
				inTag = true
			}
		case xml.EndElement:
			if t.Name.Local == tag {
				inTag = false
			}
			if paraTags[t.Name.Local] {
				sb.WriteByte('\n')
			}
		case xml.CharData:
			if inTag {
				sb.Write(t)
			}
		}
		if sb.Len() >= maxExtractText {
			break
		}
	}
	return sb.String(), nil
}

func truncate(sb *strings.Builder) string {
	s := sb.String()
	if len(s) > maxExtractText {
		return s[:maxExtractText]
	}
	return s
}

// sortIn 按 order 中的相对顺序稳定重排 names（order 之外的自然序在后）。
func sortIn(names, order []string) []string {
	rank := make(map[string]int, len(order))
	for i, n := range order {
		rank[n] = i
	}
	sort.SliceStable(names, func(i, j int) bool {
		ri, oki := rank[names[i]]
		rj, okj := rank[names[j]]
		switch {
		case oki && okj:
			return ri < rj
		case oki:
			return true
		case okj:
			return false
		}
		return names[i] < names[j]
	})
	return names
}

func slideNum(name string) int {
	base := name
	if i := strings.LastIndexByte(base, '/'); i >= 0 {
		base = base[i+1:]
	}
	base = strings.TrimSuffix(base, ".xml")
	base = strings.TrimPrefix(base, "notesSlide")
	base = strings.TrimPrefix(base, "slide")
	n, _ := strconv.Atoi(base)
	return n
}

func pathExt(p string) string {
	if i := strings.LastIndexByte(p, '.'); i >= 0 {
		return p[i:]
	}
	return ""
}
