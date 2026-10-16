package core

// PDF 文本抽取（M3）：基于 ledongthuc/pdf（rsc.io/pdf 血统，BSD-3，纯 Go）。
//
// 为什么不用它的 Page.GetPlainText：其 utf16Decode 对"奇数长度字节串"
// 会 s[i+1] 越界 panic（实测 reportlab CID 字体 PDF 必现），整页直接
// 失败。本文件用其全部导出的底层原语（Interpret/Stack/Value/Font.
// Encoder）复刻抽取流程，并在两处加固：
//   1. decodeSafe：编码器 Decode panic（UCS2 奇数尾字节、畸形 ToUnicode
//      CMap 等）时回退到自实现的 UTF-16BE 容错解码；
//   2. 整页 Interpret 包 recover：个别损坏内容流最多损失单页。
//
// 覆盖面：常规非加密 PDF 的内容流文本，含 WinAnsi 单字节字体与
// UniGB-UCS2-H/Identity-H/ToUnicode CMap 的 Type0/CID 子集字体
// （中文 PDF 主流形态）。加密文档、纯扫描件返回错误或空文本，
// 调用方按"解析失败"处理，仅该文档不参与全文索引。

import (
	"strings"
	"unicode/utf16"
	"unicode/utf8"

	"github.com/ledongthuc/pdf"
)

// extractPDF 抽取 PDF 全文，页与页之间以换行分隔。
func extractPDF(path string) (string, error) {
	f, r, err := pdf.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()

	total := r.NumPage()
	if total <= 0 {
		return "", ErrUnsupportedFormat
	}

	var sb strings.Builder
	for i := 1; i <= total; i++ {
		p := r.Page(i)
		if p.V.IsNull() {
			continue
		}
		txt := extractPageText(p)
		if txt == "" {
			continue
		}
		if sb.Len() > 0 {
			sb.WriteByte('\n')
		}
		sb.WriteString(txt)
	}
	return sb.String(), nil
}

// extractPageText 解析单页内容流的文本显示操作符。
// 内容流损坏最多损失该页，绝不 panic。
func extractPageText(p pdf.Page) (out string) {
	defer func() { _ = recover() }() // Interpret 内部按 panic 报解析错误

	var sb strings.Builder
	fonts := make(map[string]pdf.Font, 4)
	for _, name := range p.Fonts() {
		fonts[name] = p.Font(name)
	}
	// cur 当前文本状态对应的编码器（Tf 切换；初始未知 → 原样透传）。
	var cur pdf.TextEncoding
	setFont := func(name string) {
		if f, ok := fonts[name]; ok {
			cur = safeEncoder{f.Encoder()}
		}
	}
	show := func(raw string) {
		sb.WriteString(decodeTextSafe(cur, raw))
	}

	pdf.Interpret(p.V.Key("Contents"), func(stk *pdf.Stack, op string) {
		n := stk.Len()
		args := make([]pdf.Value, n)
		for i := n - 1; i >= 0; i-- { // 与 popArgs 相同：末次压栈者排 args[0]
			args[i] = stk.Pop()
		}

		switch op {
		case "BT", "T*":
			sb.WriteString("\n")
		case "Tf":
			if len(args) >= 1 && args[0].Kind() == pdf.Name {
				setFont(args[0].Name())
			}
		case "Tj":
			if len(args) >= 1 && args[0].Kind() == pdf.String {
				show(args[0].RawString())
			}
		case "'", "\"": // 换行并显示
			if len(args) >= 1 && args[0].Kind() == pdf.String {
				sb.WriteString("\n")
				show(args[0].RawString())
			}
		case "TJ":
			if len(args) >= 1 && args[0].Kind() == pdf.Array {
				v := args[0]
				for i := 0; i < v.Len(); i++ {
					x := v.Index(i)
					if x.Kind() == pdf.String {
						show(x.RawString())
					}
				}
			}
		}
	})
	return sb.String()
}

// safeEncoder 包装库内编码器：任何 panic（奇数 UCS2 尾字节、畸形
// CMap 等）回退到 utf16BESafe 的容错解码，绝不向上传播。
type safeEncoder struct{ inner pdf.TextEncoding }

func (e safeEncoder) Decode(raw string) (text string) {
	if e.inner == nil {
		return raw
	}
	defer func() {
		if recover() != nil {
			text = utf16BESafe(raw)
		}
	}()
	return e.inner.Decode(raw)
}

// decodeTextSafe 编码器解码（nil 编码器原样透传，与库内 nopEncoder 一致）。
func decodeTextSafe(enc pdf.TextEncoding, raw string) string {
	if enc == nil {
		return raw
	}
	var b strings.Builder
	for _, ch := range enc.Decode(raw) {
		b.WriteRune(ch)
	}
	return b.String()
}

// utf16BESafe UTF-16BE 容错解码：奇数尾字节丢弃、非法代理对跳过。
func utf16BESafe(s string) string {
	if len(s) < 2 {
		return ""
	}
	u := make([]uint16, 0, len(s)/2)
	for i := 0; i+1 < len(s); i += 2 {
		u = append(u, uint16(s[i])<<8|uint16(s[i+1]))
	}
	r := utf16.Decode(u)
	// 无效 rune（代理对残留 U+FFFD）在 normalizeForStore 还会统一清洗，
	// 这里仅保证不产出非法 UTF-8 字节流。
	if !utf8.ValidString(string(r)) {
		return ""
	}
	return string(r)
}
