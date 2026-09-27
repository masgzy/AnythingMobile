package core

import (
	"archive/zip"
	"bufio"
	"bytes"
	"errors"
	"io"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"
)

// ErrUnsupportedFormat 暂不支持该格式的正文抽取。
var ErrUnsupportedFormat = errors.New("core: 暂不支持该格式的正文抽取")

// maxExtractText 单文档抽取文本的上限（1MB），超出截断。
const maxExtractText = 1 << 20

// ExtractText 抽取内置支持格式（docx/pptx/xlsx/pdf）文档的纯文本。
// OOXML 家族仅依赖标准库（zip + encoding/xml）；PDF 走 ledongthuc/pdf
// （BSD-3）。.doc/.ppt/.xls 由宿主 ExternalParser 兜底（见 external.go）。
func ExtractText(path string) (string, error) {
	switch strings.ToLower(pathExt(path)) {
	case ".docx":
		return extractEntries(path, nil, exactFilter("word/document.xml"), "t", map[string]bool{"p": true})
	case ".pptx":
		return extractPptx(path)
	case ".xlsx":
		return extractXlsx(path)
	case ".pdf":
		return extractPDF(path)
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

// captureXML 流式提取名为 tag（按本地名匹配，忽略命名空间前缀）的元素的
// 字符数据；结束标签命中 paraTags 时插入换行。容错优先：损坏 XML
// 返回已收集内容，绝不报错中断。
//
// alpha11 性能改造：旧实现基于 encoding/xml 的 Token() 逐 token 反射式
// 解析（数十 MB/s），是文档解析的 CPU 大头。OOXML 正文抽取只需要
// "取指定元素的文本"这一件事，手写字节流扫描器可达数百 MB/s，
// 且不引入任何第三方依赖。语义与旧实现保持一致：
//   - 仅收集目标元素的 CharData（属性、注释、PI、DOCTYPE 均忽略）；
//   - CDATA 内容同属文本（不解码实体）；
//   - 实体解码：五个预定义 + 数字字符引用；未知实体按字面透传
//     （与 encoding/xml 非严格模式一致）；
//   - \r、\r\n 归一化为 \n；自闭合的 paraTag 元素也插入换行；
//   - 非 XML 输入（首字符不是 '<'）返回空串。
func captureXML(r io.Reader, tag string, paraTags map[string]bool) (string, error) {
	s := xmlScanner{
		br:       bufio.NewReaderSize(r, xmlChunk),
		buf:      make([]byte, 0, xmlChunk),
		tag:      tag,
		paraTags: paraTags,
	}
	s.scan()
	return s.sb.String(), nil
}

const xmlChunk = 64 << 10

// xmlScanner 手写流式 XML 文本扫描器。
// 缓冲区管理采用"消费-压缩-增长"策略：fill() 把未消费字节搬到头部后
// 追加新数据，超长 token（无 '<' 的巨型文本、超长注释）自动倍增容量，
// 单文档内存占用与 token 长度成正比而非与整个 XML 成正比。
type xmlScanner struct {
	br       *bufio.Reader
	buf      []byte
	pos      int
	eof      bool
	tag      string
	paraTags map[string]bool
	sb       strings.Builder
	inTag    bool   // 当前位于目标元素的字符数据内
	nameBuf  []byte // 复用的标签名收集缓冲（fill 压缩会搬移 buf，
	//  不能持有跨 fill 的切片偏移，故逐字节收集）
	stack []string // 打开元素的本地名栈（镜像 Go 非严格自动闭合语义）
	// nodeBuf 当前文本节点缓冲：Go 在实体解析中途遇 EOF 时丢弃整个
	// CharData 节点，故先缓冲、节点完整判定后一次性落账（flushNode）。
	nodeBuf strings.Builder
}

var (
	cdataOpen  = []byte("[CDATA[")
	cdataClose = []byte("]]>")
)

// scan 主循环：按 '<' 分类驱动状态。根元素前的游离文本/空白由
// scanText 消费（inTag 恒为 false，不输出），与 encoding/xml 对
// 前置 CharData 的处理一致。
func (s *xmlScanner) scan() {
	for s.sb.Len() < maxExtractText {
		if s.pos >= len(s.buf) {
			if !s.fill() {
				return
			}
			continue
		}
		if s.buf[s.pos] != '<' {
			s.scanText()
			continue
		}
		s.pos++
		if !s.ensure(1) {
			return
		}
		switch s.buf[s.pos] {
		case '!':
			s.pos++
			if !s.ensure(7) {
				return // EOF 尾部残片：等同解析失败，返回已收集内容
			}
			switch {
			case s.buf[s.pos] == '-' && s.buf[s.pos+1] == '-':
				s.pos += 2
				s.skipComment()
			case bytes.HasPrefix(s.buf[s.pos:], cdataOpen):
				s.pos += len(cdataOpen)
				s.scanCDATA()
			default:
				s.skipDecl() // DOCTYPE（含内部子集）
			}
		case '?':
			s.pos++
			s.skipPI()
		case '/':
			s.pos++
			if !s.scanEndTag() {
				return // 空栈结束标签：语法错误，等同旧实现终止
			}
		default:
			s.scanStartTag()
		}
	}
}

// applyEnd 应用一个结束事件：目标元素退出、paraTag 插入换行。
// 错配结束标签与 needClose 合成结束标签均复用此逻辑。
func (s *xmlScanner) applyEnd(local string) {
	if local == s.tag {
		s.inTag = false
	}
	if s.paraTags[local] {
		s.sb.WriteByte('\n')
	}
}

// scanText 收集直到下一个 '<' 的文本（仅在目标元素内写入）。
// 保留尾部 13 字节等待跨块边界补齐：实体引用最长 12 字节，
// '\r\n' 可能跨块分裂；段尾孤立 '\r' 不立即归一化，留待下轮合并判定。
//
// 文本节点按节点缓冲（nodeBuf）：实体解析中途遇 EOF 时 Go 会丢弃
// 整个 CharData 节点（text 返回 nil），因此节点必须完整判定后一次性落账。
func (s *xmlScanner) scanText() {
	const keep = 13
	for {
		seg := s.buf[s.pos:]
		if i := bytes.IndexByte(seg, '<'); i >= 0 {
			s.emitText(seg[:i])
			s.flushNode() // 节点完整：正常落账
			s.pos += i
			return
		}
		if s.eof {
			// EOF：节点内实体解析未完成时 Go 丢弃整个节点，
			// 否则正常输出（text 正常 break Input）。
			if !s.emitText(seg) {
				s.flushNode()
			}
			s.nodeBuf.Reset()
			s.pos = len(s.buf)
			return
		}
		n := len(seg)
		if n > keep {
			n -= keep
			if seg[n-1] == '\r' {
				n-- // '\r\n' 可能跨块，'\r' 留到下轮
			}
			// 实体引用跨界保护：发出段末 12 字节内出现 '&' 时整体留给
			// 下轮（保留区 ≥13 字节，合法实体最长 12 字节必完整），
			// 否则实体会被拆成字面文本，与参考实现解码结果不一致。
			for k := n - 1; k >= 0 && (n-1)-k < entityMaxLen; k-- {
				if seg[k] == '&' {
					n = k
					break
				}
			}
			if n > 0 {
				s.emitText(seg[:n])
				s.pos += n
			}
		}
		s.fill()
	}
}

// scanCDATA 收集 CDATA 内容（不解码实体），直到 "]]>"。
// 与文本节点同样按节点缓冲：EOF 处未闭合的 CDATA 在 Go 中
// 报错丢弃（rawRead 返回 nil），故节点不完整即整体丢弃。
func (s *xmlScanner) scanCDATA() {
	for {
		seg := s.buf[s.pos:]
		if i := bytes.Index(seg, cdataClose); i >= 0 {
			s.emitRaw(seg[:i])
			s.flushNode()
			s.pos += i + 3
			return
		}
		if s.eof {
			s.emitRaw(seg) // 写入 nodeBuf 但不 flush —— 丢弃
			s.nodeBuf.Reset()
			s.pos = len(s.buf)
			return
		}
		n := len(seg)
		if n > 2 {
			n -= 2 // 末 2 字节可能是 "]]>" 前缀
			if seg[n-1] == '\r' {
				n--
			}
			if n > 0 {
				s.emitRaw(seg[:n])
				s.pos += n
			}
		}
		s.fill()
	}
}

// scanStartTag 解析开始标签：取限定名，跳过属性（引号内 '>' 合法），
// 检测自闭合。语义对齐 encoding/xml：普通开始标签压栈并命中置位；
// 自闭合标签被 Token 展开为 Start+End 连发 —— 先置位再立即应用
// 结束事件（净效果 inTag=false；自闭合 paraTag 也插入换行）。
func (s *xmlScanner) scanStartTag() {
	s.nameBuf = s.nameBuf[:0]
	for {
		if !s.ensure(1) {
			return // EOF 中断：静默放弃（与旧实现解析失败等价）
		}
		c := s.buf[s.pos]
		if c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '/' || c == '>' {
			break
		}
		s.nameBuf = append(s.nameBuf, c)
		s.pos++
	}
	local := localName(s.nameBuf)

	selfClose := false
	inQuote := byte(0)
	for {
		if s.pos >= len(s.buf) {
			if !s.fill() {
				return
			}
			continue
		}
		c := s.buf[s.pos]
		if inQuote != 0 {
			if c == inQuote {
				inQuote = 0
			}
			s.pos++
			continue
		}
		switch c {
		case '"', '\'':
			inQuote = c
		case '/':
			selfClose = true
		case '>':
			s.pos++
			if local == s.tag {
				s.inTag = true // StartElement
			}
			if selfClose {
				// Token 展开：EndElement 立即到达，走正常匹配弹出（无合成）
				s.applyEnd(local)
			} else {
				s.stack = append(s.stack, local)
			}
			return
		}
		s.pos++
	}
}

// scanEndTag 解析结束标签 </name>。空栈结束标签在 encoding/xml 中
// 是语法错误（旧实现随之终止），返回 false 结束扫描；错配结束标签
// 在非严格模式下被重命名为栈顶元素并立即闭合，随后 needClose
// 合成一枚原始名的 EndElement —— 两次 applyEnd。
func (s *xmlScanner) scanEndTag() bool {
	s.nameBuf = s.nameBuf[:0]
	for {
		if s.pos >= len(s.buf) {
			if !s.fill() {
				return false
			}
			continue
		}
		c := s.buf[s.pos]
		if c == '>' || c == ' ' || c == '\t' || c == '\n' || c == '\r' {
			break
		}
		s.nameBuf = append(s.nameBuf, c)
		s.pos++
	}
	name := localName(s.nameBuf) // 栈内存储的是本地名，比较前先去前缀
	for {                        // 跳到 '>'
		if s.pos >= len(s.buf) {
			if !s.fill() {
				return false
			}
			continue
		}
		if s.buf[s.pos] == '>' {
			s.pos++
			break
		}
		s.pos++
	}
	if len(s.stack) == 0 {
		return false
	}
	for {
		top := s.stack[len(s.stack)-1]
		s.stack = s.stack[:len(s.stack)-1]
		if top == name {
			// 正常匹配：captureXML 收到原始名的 EndElement
			s.applyEnd(name)
			return true
		}
		// 错配：非严格模式把结束标签重命名为栈顶元素先闭合；
		// needClose 合成的原始名结束标签下一轮继续与更外层比对，
		// 逐层向外闭合（rename 链），直到匹配或——
		s.applyEnd(top)
		if len(s.stack) == 0 {
			// —— 栈空后合成结束标签在 popElement 中报
			// "unexpected end element" 语法错误，解码器死亡，扫描终止。
			return false
		}
	}
}

// skipComment 跳过 <!-- ... -->（末 2 字节保留防跨块分裂）。
func (s *xmlScanner) skipComment() {
	for {
		seg := s.buf[s.pos:]
		if i := bytes.Index(seg, []byte("-->")); i >= 0 {
			s.pos += i + 3
			return
		}
		if s.eof {
			s.pos = len(s.buf)
			return
		}
		if len(seg) > 2 {
			s.pos += len(seg) - 2
		}
		s.fill()
	}
}

// skipPI 跳过 <? ... ?>（末 1 字节保留防跨块分裂）。
func (s *xmlScanner) skipPI() {
	for {
		seg := s.buf[s.pos:]
		if i := bytes.Index(seg, []byte("?>")); i >= 0 {
			s.pos += i + 2
			return
		}
		if s.eof {
			s.pos = len(s.buf)
			return
		}
		if len(seg) > 1 {
			s.pos += len(seg) - 1
		}
		s.fill()
	}
}

// skipDecl 跳过 DOCTYPE 声明：括号跟踪内部子集深度，
// 引号内的 '>' 不结束声明（如 <!ENTITY x ">">）。
func (s *xmlScanner) skipDecl() {
	depth := 0
	inQuote := byte(0)
	for {
		if s.pos >= len(s.buf) {
			if !s.fill() {
				return
			}
			continue
		}
		c := s.buf[s.pos]
		switch {
		case inQuote != 0:
			if c == inQuote {
				inQuote = 0
			}
		case c == '"' || c == '\'':
			inQuote = c
		case c == '[':
			depth++
		case c == ']':
			depth--
		case c == '>' && depth <= 0:
			s.pos++
			return
		}
		s.pos++
	}
}

// emitText 写入文本节点缓冲：\r 归一化 + 实体解码，仅目标元素内生效。
// 返回值 midEntity 表示节点末尾停在实体解析中（EOF 场景，调用方丢弃节点）。
// 输出总量不超过 maxExtractText（与旧实现 token 后检查等效，最终由
// truncate 统一截断）。
func (s *xmlScanner) emitText(b []byte) (midEntity bool) {
	if !s.inTag || len(b) == 0 {
		return false
	}
	if room := maxExtractText - s.sb.Len() - s.nodeBuf.Len(); room < len(b) {
		if room <= 0 {
			return false
		}
		b = b[:room]
	}
	for i := 0; i < len(b); {
		c := b[i]
		switch {
		case c == '\r':
			s.nodeBuf.WriteByte('\n')
			i++
			if i < len(b) && b[i] == '\n' {
				i++
			}
		case c == '&':
			if n, mid := decodeEntity(b[i:], &s.nodeBuf); mid {
				// 实体解析停在缓冲末尾：字面透传已解析的原始字节。
				// '<' 边界场景 Go 也是字面输出；EOF 场景整个节点将被丢弃，
				// 此处写入无副作用。
				s.nodeBuf.Write(b[i : i+n])
				return true
			} else {
				i += n
			}
		default:
			s.nodeBuf.WriteByte(c)
			i++
		}
	}
	return false
}

// emitRaw 写入 CDATA 节点缓冲：仅做 \r 归一化，不解码实体。
func (s *xmlScanner) emitRaw(b []byte) {
	if !s.inTag || len(b) == 0 {
		return
	}
	if room := maxExtractText - s.sb.Len() - s.nodeBuf.Len(); room < len(b) {
		if room <= 0 {
			return
		}
		b = b[:room]
	}
	for i := 0; i < len(b); i++ {
		if b[i] == '\r' {
			s.nodeBuf.WriteByte('\n')
			if i+1 < len(b) && b[i+1] == '\n' {
				i++
			}
			continue
		}
		s.nodeBuf.WriteByte(b[i])
	}
}

// flushNode 把当前文本节点缓冲一次性落入输出（节点完整时调用）。
func (s *xmlScanner) flushNode() {
	s.sb.WriteString(s.nodeBuf.String())
	s.nodeBuf.Reset()
}

// entityMaxLen 合法实体引用的最大字节数（&#x0010FFFF; = 12）。
const entityMaxLen = 12

// decodeEntity 解码实体引用，镜像 encoding/xml（Strict=false）的确切语义：
//   - 预定义五实体 → 解码；未知命名实体（名字为合法 XML 名）→ 整体字面透传；
//   - 数字引用 → 解码（超界/非法值 → 字面透传）；
//   - 名字/数字非法或无 ';' → 仅消费 '&' 本身，后续字节重新处理
//     （Go 在此 ungetc 回退，让下一个 '&' 重新起头）。
//
// midEOF 表示解析停在缓冲末尾（实体名/数字尚未完结）：n 为已解析的
// 字节数且未写入任何输出，调用方应字面透传 b[:n]——'< 结尾场景 Go
// 同样按字面输出；真 EOF 场景 Go 丢弃整个文本节点（由调用方处理）。
// 其余场景返回已消费字节数（输出已写入 sb）。
// b[0] 必须是 '&'。
func decodeEntity(b []byte, sb *strings.Builder) (n int, midEOF bool) {
	if len(b) == 1 {
		return 1, true // 仅 '&'：解析停在缓冲末尾，字面透传
	}
	if b[1] == '#' {
		i := 2
		base := 10
		if i < len(b) && b[i] == 'x' { // 仅小写 x，与 Go 一致
			base = 16
			i++
		}
		if i >= len(b) {
			return i, true // '&#[x]' 后缓冲结束：解析中断，字面透传
		}
		start := i
		for i < len(b) && isDigitBase(b[i], base) {
			i++
		}
		if i >= len(b) {
			return i, true // 数字中途缓冲结束：解析中断，字面透传
		}
		if b[i] == ';' && i > start {
			if v, err := strconv.ParseUint(string(b[start:i]), base, 64); err == nil && v <= utf8.MaxRune {
				sb.WriteRune(rune(v))
				return i + 1, false
			}
		}
		// 数字残缺或值非法：字面输出 &#[x]digits（非法字符留给后续处理）
		sb.Write(b[:i])
		return i, false
	}
	j := 1
	for j < len(b) && isNameByte(b[j]) {
		j++
	}
	if j >= len(b) {
		return j, true // 名字中途缓冲结束：解析中断，字面透传
	}
	if j > 1 && b[j] == ';' {
		switch string(b[1:j]) {
		case "amp":
			sb.WriteByte('&')
		case "lt":
			sb.WriteByte('<')
		case "gt":
			sb.WriteByte('>')
		case "apos":
			sb.WriteByte('\'')
		case "quot":
			sb.WriteByte('"')
		default:
			sb.Write(b[:j+1]) // 未知实体：整体字面透传
		}
		return j + 1, false
	}
	// 名字非法或无 ';'：只消费 '&'（Go 回退后重扫，等价输出）
	sb.WriteByte('&')
	return 1, false
}

// isDigitBase c 是否为指定进制的数字字符。
func isDigitBase(c byte, base int) bool {
	switch {
	case '0' <= c && c <= '9':
		return true
	case base == 16:
		return ('a' <= c && c <= 'f') || ('A' <= c && c <= 'F')
	}
	return false
}

// isNameByte 实体名字符的近似判定（ASCII 名字字符 + 高位字节）。
// 已知实体名均为 ASCII；高位字节合并处理仅影响切分点，
// 不影响输出（未知实体一律字面透传）。
func isNameByte(c byte) bool {
	switch {
	case 'a' <= c && c <= 'z', 'A' <= c && c <= 'Z', '0' <= c && c <= '9':
		return true
	case c == '_' || c == '.' || c == '-' || c == ':':
		return true
	case c >= 0x80:
		return true
	}
	return false
}

// localName 去掉命名空间前缀："w:t" -> "t"。
func localName(qname []byte) string {
	if i := bytes.LastIndexByte(qname, ':'); i >= 0 {
		return string(qname[i+1:])
	}
	return string(qname)
}

// fill 填充缓冲区：未消费字节搬到头部，追加新数据；
// 缓冲满时倍增容量（超长 token 场景）。返回是否读到了新数据。
func (s *xmlScanner) fill() bool {
	if s.eof {
		return false
	}
	if s.pos > 0 {
		n := copy(s.buf, s.buf[s.pos:])
		s.buf = s.buf[:n]
		s.pos = 0
	}
	if len(s.buf) == cap(s.buf) {
		nb := make([]byte, len(s.buf), cap(s.buf)*2)
		copy(nb, s.buf)
		s.buf = nb
	}
	for {
		n, err := s.br.Read(s.buf[len(s.buf):cap(s.buf)])
		if n > 0 {
			s.buf = s.buf[:len(s.buf)+n]
			return true
		}
		if err != nil {
			s.eof = true
			return false
		}
		// bufio.Read 可能返回 0,nil，继续读
	}
}

// ensure 保证缓冲内可用字节数 >= n（EOF 时返回 false）。
func (s *xmlScanner) ensure(n int) bool {
	for len(s.buf)-s.pos < n {
		if !s.fill() {
			return false
		}
	}
	return true
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
