package core

// captureXML 新旧实现的差分测试。
//
// 参考实现 captureXMLRef 是 alpha10 及之前的 encoding/xml 版本（生产代码
// 已换用手写扫描器 xmlScanner）。差分口径：
//   - 每个语料用参考实现得到期望输出；
//   - 新实现分别在 1/2/3/5/7/13/64/4096 字节分块喂入（smallReader 强制
//     fill() 边界落在任意位置），输出必须与参考实现完全一致；
//   - 已知且可接受的分歧（语料规避，见各用例注释）：
//       1) 普通文本中的 "]]>" —— encoding/xml 报错截断，扫描器按字文本；
//       2) EOF 处未闭合的 CDATA —— encoding/xml 丢弃已收集内容，
//          扫描器按容错输出剩余内容。
//   - 同一元素的前后缀不一致（如 <w:t>…</t>，本地名同、Space 异）
//     在参考实现中是致命错误且无法在本地名层面感知 —— 真实 OOXML 由
//     规范序列化器产出不会出现，生成器按前缀配对规避；
//   - 其余已知分歧（正文中的 "]]>"、EOF 处未闭合 CDATA/残缺实体）
//     见上方用例注释。

import (
	"encoding/xml"
	"io"
	"math/rand"
	"strings"
	"testing"
)

// captureXMLRef alpha10 及之前的生产实现，作为差分基准。
func captureXMLRef(r io.Reader, tag string, paraTags map[string]bool) (string, error) {
	var sb strings.Builder
	dec := xml.NewDecoder(r)
	dec.Strict = false
	inTag := false
	for {
		tok, err := dec.Token()
		if err != nil {
			break
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

// smallReader 每次 Read 最多返回 n 字节，强制扫描器 fill() 边界
// 落在输入的任意字节位置（模拟 FUSE 短读与跨块 token）。
type smallReader struct {
	r io.Reader
	n int
}

func (s *smallReader) Read(p []byte) (int, error) {
	if len(p) > s.n {
		p = p[:s.n]
	}
	return s.r.Read(p)
}

var scanPara = map[string]bool{"p": true}

// compareCapture 差分单个语料：全部分块大小下新实现输出必须与参考一致。
// 两侧输出先统一按 maxExtractText 截断（生产路径 extractEntries 会调用
// truncate，截断后语义才等价；参考实现在单 token 内不限长）。
func compareCapture(t *testing.T, name, input, tag string) {
	t.Helper()
	clamp := func(s string) string {
		if len(s) > maxExtractText {
			return s[:maxExtractText]
		}
		return s
	}
	wantRaw, _ := captureXMLRef(strings.NewReader(input), tag, scanPara)
	want := clamp(wantRaw)
	for _, chunk := range []int{1, 2, 3, 5, 7, 13, 64, 4096} {
		gotRaw, _ := captureXML(&smallReader{r: strings.NewReader(input), n: chunk}, tag, scanPara)
		got := clamp(gotRaw)
		if got != want {
			t.Fatalf("%s（chunk=%d）\n input: %q\n want:  %q\n got:   %q", name, chunk, input, want, got)
		}
	}
}

func TestCaptureXMLDiffCorpus(t *testing.T) {
	cases := []struct {
		name string
		in   string
		tag  string
	}{
		{"空输入", "", "t"},
		{"纯空白", "   \n\t  ", "t"},
		{"非XML文本", "garbage text", "t"},
		{"基本docx", `<?xml version="1.0"?><w:document xmlns:w="urn:x"><w:body><w:p><w:r><w:t>第一段</w:t></w:r></w:p><w:p><w:r><w:t>第二段</w:t></w:r></w:p></w:body></w:document>`, "t"},
		{"无前缀标签", `<root><p><t>甲</t></p><p><t>乙</t></p></root>`, "t"},
		{"预定义实体", `<t>A&amp;B&lt;C&gt;D&quot;E&apos;F</t>`, "t"},
		{"数字实体", `<t>&#20320;&#x597D; mix &#65;&#x42;</t>`, "t"},
		{"未知实体透传", `<t>a&nbsp;b&unknown;x</t>`, "t"},
		{"孤立和号", `<t>a & b &amp c</t>`, "t"},
		{"CDATA", `<t>before<![CDATA[a<b>c & raw]]>after</t>`, "t"},
		{"CDATA含换行", `<t><![CDATA[行1\n行2]]></t>`, "t"},
		{"注释含伪标签", `<root><!-- <t>不收集</t> --><t>收集</t></root>`, "t"},
		{"空注释", `<root><!----><t>x</t></root>`, "t"},
		{"PI与声明", `<?xml version="1.0" encoding="UTF-8"?><?mso-application progid="x"?><t>数据</t>`, "t"},
		{"DOCTYPE", `<!DOCTYPE document><t>正文</t>`, "t"},
		{"DOCTYPE内部子集", `<!DOCTYPE d [ <!ENTITY q "v"> <!ENTITY r "&#38;"> ]><t>正文</t>`, "t"},
		{"DOCTYPE引号内大于号", `<!DOCTYPE d SYSTEM "a>b"><t>正文</t>`, "t"},
		{"自闭合段落", `<w:p/><w:t>甲</w:t><w:p/><w:t>乙</w:t>`, "t"},
		{"自闭合目标", `<t/><t>实体</t>`, "t"},
		{"属性含大于号", `<t a="x>y" b='z>t'>内容</t>`, "t"},
		{"嵌套同名", `<t>a<t>b</t>c</t>`, "t"},
		{"目标内嵌段落", `<t>开头<p>段落</p>结尾</t>`, "t"},
		{"结束标签带空白", `<t>数据</t >`, "t"},
		{"BOM", "\ufeff<?xml version=\"1.0\"?><t>BOM文本</t>", "t"},
		{"UTF8多字节", `<w:t>中文混合Englishالعربية</w:t>`, "t"},
		{"CR归一化", "<t>甲\r乙\r\n丙\n丁</t>", "t"},
		{"目标外文本忽略", `前言<t>收集</t>后记`, "t"},
		{"属性含实体不解析", `<t x="&amp;">内容</t>`, "t"},
		{"大段文本", "<w:p><w:t>" + strings.Repeat("正文段落。", 500) + "</w:t></w:p>", "t"},
		{"超限截断", "<t>" + strings.Repeat("超长文本", 400000) + "</t>", "t"},
		{"未闭合标签EOF", `<t>残缺`, "t"},
		{"未闭合注释EOF", `<t>数据</t><!--残缺`, "t"},
		{"垃圾尾随", `<t>数据</t>`, "t"},
	}

	for _, tc := range cases {
		compareCapture(t, tc.name, tc.in, tc.tag)
	}
}

// TestCaptureXMLDiffRandom 固定种子随机生成结构化 XML 做大规模差分。
//
// 结构化生成的原因：encoding/xml 对"结束标签与栈顶元素的命名空间
// Space 不一致"（如 <t>…</w:t>）在非严格模式下也是致命错误，
// 而该信息在本地名层面的扫描器里不可见。真实 OOXML 由规范序列化器
// 产出，同一元素的前后缀必然一致，故生成器按前缀配对构造标签，
// 错配关闭（rename 链）仍会自然出现——以栈内更外层元素为关闭目标。
func TestCaptureXMLDiffRandom(t *testing.T) {
	tags := []string{"t", "w:t", "p", "w:p", "w:r", "other", "a"}
	// 文本类片段：实体、裸 &、残缺实体、空白、\r 组合、注释、CDATA、PI、DOCTYPE。
	// 不含孤立 '<' 与文本内 "]]>"（前者两者都报错但报法不同、后者是
	// 参考实现报错截断的已知分歧点，见文件头注释）。
	textFrags := []string{
		`text`, `你好世界`, `&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`,
		`&#65;`, `&#x4F60;`, `&nbsp;`, `&unknown;`, `&`, `&#x`, `&#`,
		"\r", "\r\n", "\n", "\t", " ",
		`<!--c-->`, `<!---->`, `<!-- <t>x</t> -->`,
		`<![CDATA[raw<material]]>`, `<?pi v="1"?>`,
		`<!DOCTYPE d>`, `<!DOCTYPE d [ <!ENTITY q "v"> ]>`,
	}
	rnd := rand.New(rand.NewSource(20260927))

	localOf := func(name string) string {
		if i := strings.IndexByte(name, ':'); i >= 0 {
			return name[i+1:]
		}
		return name
	}

	for iter := 0; iter < 300; iter++ {
		var b strings.Builder
		b.WriteString(`<?xml version="1.0"?>`)
		var stack []string // 与扫描器同构的本地名栈（含前缀写法）

		emitClose := func(name string) {
			if i := strings.IndexByte(name, ':'); i >= 0 {
				b.WriteString(`</w:` + name[i+1:] + `>`)
			} else {
				b.WriteString(`</` + name + `>`)
			}
		}
		// 关闭栈内第 k 层元素（0=栈顶）：写入其结束标签，
		// 参考实现经 rename 链逐层闭合中间元素——两个实现同步弹栈。
		closeLevel := func(k int) {
			name := stack[len(stack)-1-k]
			emitClose(name)
			stack = stack[:len(stack)-1-k]
		}

		steps := 12 + rnd.Intn(36)
		for step := 0; step < steps; step++ {
			switch n := rnd.Intn(10); {
			case n < 4: // 文本片段
				b.WriteString(textFrags[rnd.Intn(len(textFrags))])
			case n < 7: // 开启新元素
				if len(stack) < 8 {
					name := tags[rnd.Intn(len(tags))]
					b.WriteString(`<` + name + `>`)
					stack = append(stack, name)
				}
			case n < 8: // 自闭合
				name := tags[rnd.Intn(len(tags))]
				b.WriteString(`<` + name + `/>`)
			case n < 9: // 关闭：正常关栈顶 或 错配关更外层（rename 链）
				if len(stack) > 0 {
					k := 0
					if len(stack) > 1 && rnd.Intn(3) == 0 {
						k = rnd.Intn(min(3, len(stack)))
						// rename 链要求中间层本地名与目标不同：本地名相同
						// 而前缀不同（如 <w:t> 被 </t> 关闭）会命中参考实现
						// 的 Space 致命错误，该信息在本地名层面不可见，规避。
						target := stack[len(stack)-1-k]
						for j := 0; j < k; j++ {
							if localOf(stack[len(stack)-1-j]) == localOf(target) {
								k = 0 // 退化为正常关顶
								break
							}
						}
					}
					closeLevel(k)
				} else {
					// 空栈关闭：两个实现都语法终止，后续内容不再产出
					b.WriteString(`</gone>`)
				}
			default: // 关闭全部
				for len(stack) > 0 {
					closeLevel(0)
				}
			}
		}
		for len(stack) > 0 { // 收尾关闭所有未闭合元素
			closeLevel(0)
		}
		// 尾部固定闭合标签：避免文档以残缺实体结尾——EOF 处实体中断时
		// 参考实现会丢弃整个文本节点（mustgetc 失败），那是已记录分歧
		//（真实文档以 '<' 终结文本节点，不触发）。
		b.WriteString(`<z/>`)
		compareCapture(t, "随机语料", b.String(), "t")
	}
}

// Go 1.21+ 内置 min 足以覆盖本文件的用法，不再自定义。

// TestCaptureXMLTruncationLimit 输出超过 maxExtractText 时必须截断到 1MB。
func TestCaptureXMLTruncationLimit(t *testing.T) {
	big := strings.Repeat("长", 600000) // 1.2MB UTF-8
	out, _ := captureXML(strings.NewReader("<t>"+big+"</t>"), "t", scanPara)
	if len(out) != maxExtractText {
		t.Fatalf("应截断到 %d 字节, got %d", maxExtractText, len(out))
	}
	// 截断不应破坏多字节字符（仍是合法 UTF-8 截断点由 truncate 保证，
	// 这里只验证长度上限与开头内容）
	if !strings.HasPrefix(out, "长长长") {
		t.Fatal("截断后开头内容异常")
	}
}

// TestLocalName 命名空间本地名提取。
func TestLocalName(t *testing.T) {
	cases := map[string]string{
		"t": "t", "w:t": "t", "ns12:非常长名称": "非常长名称", "a:b:c": "c",
	}
	for in, want := range cases {
		if got := localName([]byte(in)); got != want {
			t.Fatalf("localName(%q)=%q, want %q", in, got, want)
		}
	}
}
