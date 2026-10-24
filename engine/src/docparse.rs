//! 文档正文抽取 —— 移植 Go `core/docparse.go` + `pdfparse.go`。
//!
//! OOXML 家族（docx/pptx/xlsx）= zip 内 XML 的文本抽取，仅依赖 zip 解压；
//! XML 扫描器为 Go 手写字节流状态机的**逐语义直译**（重命名链/嵌套同名
//! 标签/实体跨界/EOF 丢节点全部保留），不引入通用 XML 解析器 —— 正文
//! 抽取只需要"取指定元素的文本"这一件事，手写字节流扫描器可达数百 MB/s。
//! PDF 走 pdf-extract（含 CMap/Type0 字体支持，覆盖中文 PDF 主流形态）。
//! .doc/.ppt/.xls/.wps 由宿主 ExternalParser 兜底（见 engine.rs）。

use std::collections::HashMap;
use std::io::Read;

/// 暂不支持该格式的正文抽取（Go `ErrUnsupportedFormat` 同文案）。
pub fn err_unsupported_format() -> crate::error::EngineError {
    crate::error::EngineError::new("core: 暂不支持该格式的正文抽取")
}

/// 单文档抽取文本的上限（1MB），超出截断。
pub(crate) const MAX_EXTRACT_TEXT: usize = 1 << 20;

/// 抽取内置支持格式（docx/pptx/xlsx/pdf）文档的纯文本。
pub fn extract_text(path: &str) -> crate::error::EngineResult<String> {
    match crate::textutil::ext_of(path).as_str() {
        ".docx" => extract_entries(path, None, &|name| name == "word/document.xml", "t", &["p"]),
        ".pptx" => extract_pptx(path),
        ".xlsx" => extract_xlsx(path),
        ".pdf" => extract_pdf(path),
        _ => Err(err_unsupported_format()),
    }
}

// ---------------------------------------------------------------- zip 抽取

/// 按过滤器抽取 zip 内若干 XML 的文本（Go `extractEntries` 同款）。
fn extract_entries(
    path: &str,
    order: Option<&[&str]>,
    keep: &dyn Fn(&str) -> bool,
    tag: &str,
    para_tags: &[&str],
) -> crate::error::EngineResult<String> {
    let file = std::fs::File::open(path)
        .map_err(|e| crate::error::EngineError::new(format!("core: 打开文档失败: {e}")))?;
    let mut zr = zip::ZipArchive::new(file)
        .map_err(|e| crate::error::EngineError::new(format!("core: 文档 zip 解析失败: {e}")))?;

    let mut names: Vec<String> = zr
        .file_names()
        .filter_map(|r| r.ok().map(|c| c.into_owned()))
        .filter(|n| keep(n))
        .collect();
    match order {
        None => names.sort(),
        Some(order) => sort_in(&mut names, order),
    }

    let pt: HashMap<&str, ()> = para_tags.iter().map(|t| (*t, ())).collect();
    let mut sb = String::new();
    for name in &names {
        let Ok(mut f) = zr.by_name(name) else {
            continue;
        };
        let text = capture_xml(&mut f, tag, &pt);
        drop(f);
        if !text.trim().is_empty() {
            if !sb.is_empty() {
                sb.push('\n');
            }
            sb.push_str(&text);
        }
        if sb.len() >= MAX_EXTRACT_TEXT {
            break;
        }
    }
    Ok(truncate(&sb))
}

/// 幻灯片与备注页按编号顺序抽取 <a:t> 文本（Go `extractPptx` 同款）。
fn extract_pptx(path: &str) -> crate::error::EngineResult<String> {
    let file = std::fs::File::open(path)
        .map_err(|e| crate::error::EngineError::new(format!("core: 打开文档失败: {e}")))?;
    let mut zr = zip::ZipArchive::new(file)
        .map_err(|e| crate::error::EngineError::new(format!("core: 文档 zip 解析失败: {e}")))?;

    let mut slides: Vec<String> = zr
        .file_names()
        .filter_map(|r| r.ok().map(|c| c.into_owned()))
        .filter(|n| {
            (n.starts_with("ppt/slides/slide") || n.starts_with("ppt/notesSlides/notesSlide"))
                && n.ends_with(".xml")
        })
        .collect();
    slides.sort_by_key(|n| slide_num(n));

    let pt: HashMap<&str, ()> = [("p", ())].into_iter().collect();
    let mut sb = String::new();
    for name in &slides {
        let Ok(mut f) = zr.by_name(name) else {
            continue;
        };
        let text = capture_xml(&mut f, "t", &pt);
        drop(f);
        if !text.trim().is_empty() {
            if !sb.is_empty() {
                sb.push('\n');
            }
            sb.push_str(&text);
        }
        if sb.len() >= MAX_EXTRACT_TEXT {
            break;
        }
    }
    Ok(truncate(&sb))
}

/// 抽取共享字符串表与工作表内联字符串（Go `extractXlsx` 同款）。
fn extract_xlsx(path: &str) -> crate::error::EngineResult<String> {
    let file = std::fs::File::open(path)
        .map_err(|e| crate::error::EngineError::new(format!("core: 打开文档失败: {e}")))?;
    let mut zr = zip::ZipArchive::new(file)
        .map_err(|e| crate::error::EngineError::new(format!("core: 文档 zip 解析失败: {e}")))?;

    let pt: HashMap<&str, ()> = [("row", ())].into_iter().collect();
    let mut sb = String::new();
    // zip 条目顺序即工作表顺序（Go 不排序，同款）
    let names: Vec<String> = zr
        .file_names()
        .filter_map(|r| r.ok().map(|c| c.into_owned()))
        .filter(|n| {
            n == "xl/sharedStrings.xml" || (n.starts_with("xl/worksheets/") && n.ends_with(".xml"))
        })
        .collect();
    for name in &names {
        let Ok(mut f) = zr.by_name(name) else {
            continue;
        };
        let text = capture_xml(&mut f, "t", &pt);
        drop(f);
        if !sb.is_empty() {
            sb.push('\n');
        }
        sb.push_str(&text);
        if sb.len() >= MAX_EXTRACT_TEXT {
            break;
        }
    }
    Ok(truncate(&sb))
}

// ---------------------------------------------------------------- XML 扫描器

/// 流式提取名为 tag（按本地名匹配，忽略命名空间前缀）的元素的字符数据；
/// 结束标签命中 para_tags 时插入换行。容错优先：损坏 XML 返回已收集
/// 内容，绝不报错中断（Go `captureXML` 同款）。
fn capture_xml(r: &mut dyn Read, tag: &str, para_tags: &HashMap<&str, ()>) -> String {
    let mut s = XmlScanner {
        br: r,
        buf: Vec::with_capacity(XML_CHUNK),
        pos: 0,
        eof: false,
        tag,
        para_tags,
        sb: String::new(),
        in_tag: false,
        name_buf: Vec::new(),
        stack: Vec::new(),
        node_buf: String::new(),
    };
    s.scan();
    s.sb
}

const XML_CHUNK: usize = 64 << 10;

/// 手写流式 XML 文本扫描器（Go `xmlScanner` 逐语义直译）。
/// 缓冲区管理采用"消费-压缩-增长"策略：fill() 把未消费字节搬到头部后
/// 追加新数据，超长 token（无 '<' 的巨型文本、超长注释）自动倍增容量，
/// 单文档内存占用与 token 长度成正比而非与整个 XML 成正比。
struct XmlScanner<'a> {
    br: &'a mut dyn Read,
    buf: Vec<u8>,
    pos: usize,
    eof: bool,
    tag: &'a str,
    para_tags: &'a HashMap<&'a str, ()>,
    sb: String,
    /// 当前位于目标元素的字符数据内。
    in_tag: bool,
    /// 复用的标签名收集缓冲。
    name_buf: Vec<u8>,
    /// 打开元素的本地名栈（镜像 Go 非严格自动闭合语义）。
    stack: Vec<String>,
    /// 当前文本节点缓冲：Go 在实体解析中途遇 EOF 时丢弃整个 CharData
    /// 节点，故先缓冲、节点完整判定后一次性落账（flush_node）。
    node_buf: String,
}

/// 写入文本节点缓冲：\r 归一化 + 实体解码，仅目标元素内生效
/// （in_tag 由调用方先行判定）。返回值 mid_entity 表示节点末尾停在
/// 实体解析中（EOF 场景，调用方丢弃节点）。输出总量不超过 max_extract_text。
/// 自由函数形态：允许缓冲区与节点缓冲的字段级拆分借用（Go 方法收 receiver，
/// Rust 方法收 &mut self 会与 buf 切片借用冲突）。
fn emit_text_into(b: &[u8], node_buf: &mut String, out_len: usize) -> bool {
    if b.is_empty() {
        return false;
    }
    let room = MAX_EXTRACT_TEXT.saturating_sub(out_len + node_buf.len());
    if room == 0 {
        return false;
    }
    let b = if room < b.len() { &b[..room] } else { b };
    let mut i = 0;
    while i < b.len() {
        let c = b[i];
        if c == b'\r' {
            node_buf.push('\n');
            i += 1;
            if i < b.len() && b[i] == b'\n' {
                i += 1;
            }
        } else if c == b'&' {
            let (n, mid) = decode_entity(&b[i..], node_buf);
            if mid {
                // 实体解析停在缓冲末尾：字面透传已解析的原始字节。
                // '<' 边界场景 Go 也是字面输出；EOF 场景整个节点将被丢弃，
                // 此处写入无副作用。
                for &byte in &b[i..i + n] {
                    node_buf.push(byte as char);
                }
                return true;
            }
            i += n;
        } else {
            // 多字节 UTF-8 序列整体推进（from_utf8 校验），ASCII 单字节直推
            let len = utf8_seq_len(c);
            if len > 1
                && i + len <= b.len()
                && let Ok(s) = std::str::from_utf8(&b[i..i + len])
            {
                node_buf.push_str(s);
                i += len;
                continue;
            }
            node_buf.push(c as char); // ASCII 单字节或残缺序列（字面透传）
            i += 1;
        }
    }
    false
}

/// 写入 CDATA 节点缓冲：仅做 \r 归一化，不解码实体。
fn emit_raw_into(b: &[u8], node_buf: &mut String, out_len: usize) {
    if b.is_empty() {
        return;
    }
    let room = MAX_EXTRACT_TEXT.saturating_sub(out_len + node_buf.len());
    if room == 0 {
        return;
    }
    let b = if room < b.len() { &b[..room] } else { b };
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'\r' {
            node_buf.push('\n');
            if i + 1 < b.len() && b[i + 1] == b'\n' {
                i += 1;
            }
        } else {
            let c = b[i];
            let len = utf8_seq_len(c);
            if len > 1
                && i + len <= b.len()
                && let Ok(s) = std::str::from_utf8(&b[i..i + len])
            {
                node_buf.push_str(s);
                i += len;
                continue;
            }
            node_buf.push(c as char);
        }
        i += 1;
    }
}

const CDATA_OPEN: &[u8] = b"[CDATA[";

impl<'a> XmlScanner<'a> {
    /// 主循环：按 '<' 分类驱动状态。根元素前的游离文本/空白由
    /// scan_text 消费（in_tag 恒为 false，不输出）。
    fn scan(&mut self) {
        while self.sb.len() < MAX_EXTRACT_TEXT {
            if self.pos >= self.buf.len() {
                if !self.fill() {
                    return;
                }
                continue;
            }
            if self.buf[self.pos] != b'<' {
                self.scan_text();
                continue;
            }
            self.pos += 1;
            if !self.ensure(1) {
                return;
            }
            match self.buf[self.pos] {
                b'!' => {
                    self.pos += 1;
                    if !self.ensure(7) {
                        return; // EOF 尾部残片：等同解析失败，返回已收集内容
                    }
                    if self.buf[self.pos] == b'-' && self.buf[self.pos + 1] == b'-' {
                        self.pos += 2;
                        self.skip_comment();
                    } else if self.buf[self.pos..].starts_with(CDATA_OPEN) {
                        self.pos += CDATA_OPEN.len();
                        self.scan_cdata();
                    } else {
                        self.skip_decl(); // DOCTYPE（含内部子集）
                    }
                }
                b'?' => {
                    self.pos += 1;
                    self.skip_pi();
                }
                b'/' => {
                    self.pos += 1;
                    if !self.scan_end_tag() {
                        return; // 空栈结束标签：语法错误，等同旧实现终止
                    }
                }
                _ => self.scan_start_tag(),
            }
        }
    }

    /// 应用一个结束事件：目标元素退出、para_tag 插入换行。
    /// 错配结束标签与 need_close 合成结束标签均复用此逻辑。
    fn apply_end(&mut self, local: &str) {
        if local == self.tag {
            self.in_tag = false;
        }
        if self.para_tags.contains_key(local) {
            self.sb.push('\n');
        }
    }

    /// 收集直到下一个 '<' 的文本（仅在目标元素内写入）。
    /// 保留尾部 13 字节等待跨块边界补齐：实体引用最长 12 字节，
    /// '\r\n' 可能跨块分裂；段尾孤立 '\r' 不立即归一化，留待下轮合并判定。
    fn scan_text(&mut self) {
        const KEEP: usize = 13;
        loop {
            let pos = self.pos;
            if let Some(i) = self.buf[pos..].iter().position(|&b| b == b'<') {
                let mid = self.in_tag
                    && emit_text_into(&self.buf[pos..pos + i], &mut self.node_buf, self.sb.len());
                if !mid {
                    self.flush_node(); // 节点完整：正常落账
                }
                self.pos += i;
                return;
            }
            if self.eof {
                // EOF：节点内实体解析未完成时 Go 丢弃整个节点，
                // 否则正常输出（text 正常 break Input）。
                let mid = self.in_tag
                    && emit_text_into(&self.buf[pos..], &mut self.node_buf, self.sb.len());
                if !mid {
                    self.flush_node();
                }
                self.node_buf.clear();
                self.pos = self.buf.len();
                return;
            }
            let mut n = self.buf.len() - pos;
            if n > KEEP {
                n -= KEEP;
                if self.buf[pos + n - 1] == b'\r' {
                    n -= 1; // '\r\n' 可能跨块，'\r' 留到下轮
                }
                // 实体引用跨界保护：发出段末 12 字节内出现 '&' 时整体留给
                // 下轮（保留区 ≥13 字节，合法实体最长 12 字节必完整），
                // 否则实体会被拆成字面文本，与参考实现解码结果不一致。
                let mut k = n;
                while k > 0 && (n - 1) - (k - 1) < 12 {
                    k -= 1;
                    if self.buf[pos + k] == b'&' {
                        n = k;
                        break;
                    }
                }
                if n > 0 {
                    let _mid = self.in_tag
                        && emit_text_into(
                            &self.buf[pos..pos + n],
                            &mut self.node_buf,
                            self.sb.len(),
                        );
                    let _ = _mid; // '<' 边界场景 mid 不可能为真（无 '&' 残留）
                    self.pos += n;
                }
            }
            self.fill();
        }
    }

    /// 收集 CDATA 内容（不解码实体），直到 "]]>"。
    /// 与文本节点同样按节点缓冲：EOF 处未闭合的 CDATA 在 Go 中
    /// 报错丢弃（rawRead 返回 nil），故节点不完整即整体丢弃。
    fn scan_cdata(&mut self) {
        loop {
            let pos = self.pos;
            if let Some(i) = find_subslice(&self.buf[pos..], b"]]>") {
                if self.in_tag {
                    emit_raw_into(&self.buf[pos..pos + i], &mut self.node_buf, self.sb.len());
                }
                self.flush_node();
                self.pos += i + 3;
                return;
            }
            if self.eof {
                if self.in_tag {
                    emit_raw_into(&self.buf[pos..], &mut self.node_buf, self.sb.len());
                }
                self.node_buf.clear(); // 写入 node_buf 但不 flush —— 丢弃
                self.pos = self.buf.len();
                return;
            }
            let mut n = self.buf.len() - pos;
            if n > 2 {
                n -= 2; // 末 2 字节可能是 "]]>" 前缀
                if self.buf[pos + n - 1] == b'\r' {
                    n -= 1;
                }
                if n > 0 {
                    if self.in_tag {
                        emit_raw_into(&self.buf[pos..pos + n], &mut self.node_buf, self.sb.len());
                    }
                    self.pos += n;
                }
            }
            self.fill();
        }
    }

    /// 解析开始标签：取限定名，跳过属性（引号内 '>' 合法），检测自闭合。
    /// 语义对齐 Go encoding/xml 非严格模式：普通开始标签压栈并命中置位；
    /// 自闭合标签被 Token 展开为 Start+End 连发 —— 先置位再立即应用
    /// 结束事件（净效果 in_tag=false；自闭合 para_tag 也插入换行）。
    fn scan_start_tag(&mut self) {
        self.name_buf.clear();
        loop {
            if !self.ensure(1) {
                return; // EOF 中断：静默放弃（与旧实现解析失败等价）
            }
            let c = self.buf[self.pos];
            if c == b' ' || c == b'\t' || c == b'\n' || c == b'\r' || c == b'/' || c == b'>' {
                break;
            }
            self.name_buf.push(c);
            self.pos += 1;
        }
        let local = local_name(&self.name_buf);

        let mut self_close = false;
        let mut in_quote = 0u8;
        loop {
            if self.pos >= self.buf.len() {
                if !self.fill() {
                    return;
                }
                continue;
            }
            let c = self.buf[self.pos];
            if in_quote != 0 {
                if c == in_quote {
                    in_quote = 0;
                }
                self.pos += 1;
                continue;
            }
            match c {
                b'"' | b'\'' => in_quote = c,
                b'/' => self_close = true,
                b'>' => {
                    self.pos += 1;
                    if local == self.tag {
                        self.in_tag = true; // StartElement
                    }
                    if self_close {
                        // Token 展开：EndElement 立即到达，走正常匹配弹出（无合成）
                        self.apply_end(&local);
                    } else {
                        self.stack.push(local);
                    }
                    return;
                }
                _ => {}
            }
            self.pos += 1;
        }
    }

    /// 解析结束标签 </name>。空栈结束标签在 encoding/xml 中是语法错误
    /// （旧实现随之终止），返回 false 结束扫描；错配结束标签在非严格
    /// 模式下被重命名为栈顶元素并立即闭合，随后合成一枚原始名的
    /// EndElement —— 两次 apply_end（rename 链逐层向外闭合）。
    fn scan_end_tag(&mut self) -> bool {
        self.name_buf.clear();
        loop {
            if self.pos >= self.buf.len() {
                if !self.fill() {
                    return false;
                }
                continue;
            }
            let c = self.buf[self.pos];
            if c == b'>' || c == b' ' || c == b'\t' || c == b'\n' || c == b'\r' {
                break;
            }
            self.name_buf.push(c);
            self.pos += 1;
        }
        let name = local_name(&self.name_buf); // 栈内存储的是本地名，比较前先去前缀
        loop {
            // 跳到 '>'
            if self.pos >= self.buf.len() {
                if !self.fill() {
                    return false;
                }
                continue;
            }
            if self.buf[self.pos] == b'>' {
                self.pos += 1;
                break;
            }
            self.pos += 1;
        }
        if self.stack.is_empty() {
            return false;
        }
        loop {
            let top = self.stack.pop().unwrap();
            if top == name {
                // 正常匹配：captureXML 收到原始名的 EndElement
                self.apply_end(&name);
                return true;
            }
            // 错配：重命名为栈顶元素先闭合；合成的原始名结束标签
            // 下一轮继续与更外层比对，直到匹配或——
            self.apply_end(&top);
            if self.stack.is_empty() {
                // —— 栈空后合成结束标签报语法错误，解码器死亡，扫描终止。
                return false;
            }
        }
    }

    /// 跳过 <!-- ... -->（末 2 字节保留防跨块分裂）。
    fn skip_comment(&mut self) {
        loop {
            let seg = &self.buf[self.pos..];
            if let Some(i) = find_subslice(seg, b"-->") {
                self.pos += i + 3;
                return;
            }
            if self.eof {
                self.pos = self.buf.len();
                return;
            }
            if seg.len() > 2 {
                self.pos += seg.len() - 2;
            }
            self.fill();
        }
    }

    /// 跳过 <? ... ?>（末 1 字节保留防跨块分裂）。
    fn skip_pi(&mut self) {
        loop {
            let seg = &self.buf[self.pos..];
            if let Some(i) = find_subslice(seg, b"?>") {
                self.pos += i + 2;
                return;
            }
            if self.eof {
                self.pos = self.buf.len();
                return;
            }
            if seg.len() > 1 {
                self.pos += seg.len() - 1;
            }
            self.fill();
        }
    }

    /// 跳过 DOCTYPE 声明：括号跟踪内部子集深度，引号内的 '>' 不结束
    /// 声明（如 <!ENTITY x ">">）。
    fn skip_decl(&mut self) {
        let mut depth = 0i32;
        let mut in_quote = 0u8;
        loop {
            if self.pos >= self.buf.len() {
                if !self.fill() {
                    return;
                }
                continue;
            }
            let c = self.buf[self.pos];
            if in_quote != 0 {
                if c == in_quote {
                    in_quote = 0;
                }
            } else if c == b'"' || c == b'\'' {
                in_quote = c;
            } else if c == b'[' {
                depth += 1;
            } else if c == b']' {
                depth -= 1;
            } else if c == b'>' && depth <= 0 {
                self.pos += 1;
                return;
            }
            self.pos += 1;
        }
    }

    /// 把当前文本节点缓冲一次性落入输出（节点完整时调用）。
    fn flush_node(&mut self) {
        self.sb.push_str(&self.node_buf);
        self.node_buf.clear();
    }

    /// 填充缓冲区：未消费字节搬到头部，追加新数据；缓冲满时倍增容量
    /// （超长 token 场景）。返回是否读到了新数据。
    fn fill(&mut self) -> bool {
        if self.eof {
            return false;
        }
        if self.pos > 0 {
            self.buf.drain(..self.pos);
            self.pos = 0;
        }
        if self.buf.len() == self.buf.capacity() {
            self.buf.reserve(self.buf.capacity().max(XML_CHUNK));
        }
        loop {
            let old_len = self.buf.len();
            self.buf.resize(self.buf.capacity(), 0);
            match self.br.read(&mut self.buf[old_len..]) {
                Ok(0) => {
                    self.buf.truncate(old_len);
                    self.eof = true;
                    return false;
                }
                Ok(n) => {
                    self.buf.truncate(old_len + n);
                    return true;
                }
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {
                    self.buf.truncate(old_len);
                    continue;
                }
                Err(_) => {
                    self.buf.truncate(old_len);
                    self.eof = true;
                    return false;
                }
            }
        }
    }

    /// 保证缓冲内可用字节数 >= n（EOF 时返回 false）。
    fn ensure(&mut self, n: usize) -> bool {
        while self.buf.len() - self.pos < n {
            if !self.fill() {
                return false;
            }
        }
        true
    }
}

/// UTF-8 序列长度（按首字节；非法首字节按 1 处理，字面透传）。
fn utf8_seq_len(b: u8) -> usize {
    match b {
        0x00..=0x7F => 1,
        0xC0..=0xDF => 2,
        0xE0..=0xEF => 3,
        0xF0..=0xF7 => 4,
        _ => 1,
    }
}

/// 在字节切片中查找子串（Go bytes.Index 同款语义）。
fn find_subslice(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || haystack.len() < needle.len() {
        return None;
    }
    haystack.windows(needle.len()).position(|w| w == needle)
}

/// 解码实体引用，镜像 Go encoding/xml（Strict=false）的确切语义：
/// - 预定义五实体 → 解码；未知命名实体（名字为合法 XML 名）→ 整体字面透传；
/// - 数字引用 → 解码（超界/非法值 → 字面透传）；
/// - 名字/数字非法或无 ';' → 仅消费 '&' 本身，后续字节重新处理；
/// - mid_eof 表示解析停在缓冲末尾（实体名/数字尚未完结）：n 为已解析的
///   字节数且未写入任何输出，调用方应字面透传 b[:n]。
///   b[0] 必须是 '&'。
fn decode_entity(b: &[u8], sb: &mut String) -> (usize, bool) {
    if b.len() == 1 {
        return (1, true); // 仅 '&'：解析停在缓冲末尾，字面透传
    }
    if b[1] == b'#' {
        let mut i = 2;
        let mut base = 10u32;
        if i < b.len() && b[i] == b'x' {
            // 仅小写 x，与 Go 一致
            base = 16;
            i += 1;
        }
        if i >= b.len() {
            return (i, true); // '&#[x]' 后缓冲结束：解析中断，字面透传
        }
        let start = i;
        while i < b.len() && is_digit_base(b[i], base) {
            i += 1;
        }
        if i >= b.len() {
            return (i, true); // 数字中途缓冲结束：解析中断，字面透传
        }
        if b[i] == b';' && i > start {
            let digits = std::str::from_utf8(&b[start..i]).unwrap_or("");
            if let Ok(v) = u64::from_str_radix(digits, base)
                && v <= 0x10FFFF
                && let Some(ch) = char::from_u32(v as u32)
            {
                sb.push(ch);
                return (i + 1, false);
            }
        }
        // 数字残缺或值非法：字面输出 &#[x]digits（非法字符留给后续处理）
        for &byte in &b[..i] {
            sb.push(byte as char);
        }
        return (i, false);
    }
    let mut j = 1;
    while j < b.len() && is_name_byte(b[j]) {
        j += 1;
    }
    if j >= b.len() {
        return (j, true); // 名字中途缓冲结束：解析中断，字面透传
    }
    if j > 1 && b[j] == b';' {
        match &b[1..j] {
            b"amp" => sb.push('&'),
            b"lt" => sb.push('<'),
            b"gt" => sb.push('>'),
            b"apos" => sb.push('\''),
            b"quot" => sb.push('"'),
            raw => {
                // 未知实体：整体字面透传（含引导 '&' 与收尾 ';'）
                sb.push('&');
                for &byte in raw {
                    sb.push(byte as char);
                }
                sb.push(';');
            }
        }
        return (j + 1, false);
    }
    // 名字非法或无 ';'：只消费 '&'（Go 回退后重扫，等价输出）
    sb.push('&');
    (1, false)
}

/// c 是否为指定进制的数字字符。
fn is_digit_base(c: u8, base: u32) -> bool {
    match c {
        b'0'..=b'9' => true,
        _ if base == 16 => (b'a'..=b'f').contains(&c) || (b'A'..=b'F').contains(&c),
        _ => false,
    }
}

/// 实体名字符的近似判定（ASCII 名字字符 + 高位字节）。
/// 已知实体名均为 ASCII；高位字节合并处理仅影响切分点，不影响输出
/// （未知实体一律字面透传）。
fn is_name_byte(c: u8) -> bool {
    matches!(c, b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'_' | b'.' | b'-' | b':') || c >= 0x80
}

/// 去掉命名空间前缀："w:t" -> "t"。
fn local_name(qname: &[u8]) -> String {
    match qname.iter().rposition(|&b| b == b':') {
        Some(i) => String::from_utf8_lossy(&qname[i + 1..]).into_owned(),
        None => String::from_utf8_lossy(qname).into_owned(),
    }
}

// ---------------------------------------------------------------- PDF

/// 抽取 PDF 全文（pdf-extract：含 WinAnsi/Type0/CMap/ToUnicode 支持；
/// vendor 补丁另支持 UniGB-UCS2 等 CJK 预定义 CMap，见 vendor/pdf-extract/。
/// 加密文档与纯扫描件返回错误）。
/// 整体 catch_unwind 护罩（Go 版 recover 同语义）：个别损坏内容流
/// 最多损失该文档，panic 绝不向上传播。
fn extract_pdf(path: &str) -> crate::error::EngineResult<String> {
    let result: Result<Result<String, pdf_extract::OutputError>, Box<dyn std::any::Any + Send>> =
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            pdf_extract::extract_text(path)
        }));
    match result {
        Ok(Ok(t)) => Ok(truncate(&t)),
        Ok(Err(e)) => Err(crate::error::EngineError::new(format!(
            "core: PDF 解析失败: {e}"
        ))),
        Err(_) => Err(crate::error::EngineError::new(
            "core: PDF 解析失败: 内部错误",
        )),
    }
}

// ---------------------------------------------------------------- 工具

fn truncate(s: &str) -> String {
    crate::textutil::truncate_runes(s, MAX_EXTRACT_TEXT).to_string()
}

/// 按 order 中的相对顺序稳定重排 names（order 之外的自然序在后）。
fn sort_in(names: &mut [String], order: &[&str]) {
    let mut rank: HashMap<&str, usize> = HashMap::with_capacity(order.len());
    for (i, n) in order.iter().enumerate() {
        rank.insert(n, i);
    }
    names.sort_by(|a, b| {
        let ra = rank.get(a.as_str());
        let rb = rank.get(b.as_str());
        match (ra, rb) {
            (Some(x), Some(y)) => x.cmp(y),
            (Some(_), None) => std::cmp::Ordering::Less,
            (None, Some(_)) => std::cmp::Ordering::Greater,
            (None, None) => a.cmp(b),
        }
    });
}

/// 幻灯片编号：slide3.xml → 3；notesSlide10.xml → 10（Go 同款）。
fn slide_num(name: &str) -> i64 {
    let mut base = crate::textutil::base_name(name);
    base = base.strip_suffix(".xml").unwrap_or(base);
    base = base.strip_prefix("notesSlide").unwrap_or(base);
    base = base.strip_prefix("slide").unwrap_or(base);
    base.parse().unwrap_or(0)
}

// ---------------------------------------------------------------- 测试

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn cap(s: &str, tag: &str, paras: &[&str]) -> String {
        let pt: HashMap<&str, ()> = paras.iter().map(|t| (*t, ())).collect();
        capture_xml(&mut Cursor::new(s.as_bytes()), tag, &pt)
    }

    #[test]
    fn basic_text_collection() {
        assert_eq!(cap("<a><t>hello</t></a>", "t", &[]), "hello");
        // 本地名匹配（忽略命名空间前缀）
        assert_eq!(cap("<w:r><w:t>中文</w:t></w:r>", "t", &[]), "中文");
        // 多个目标元素顺序拼接
        assert_eq!(cap("<a><t>x</t><b>skip</b><t>y</t></a>", "t", &[]), "xy");
    }

    #[test]
    fn para_tag_newline_and_self_closing() {
        // Go 差分语料"无前缀标签"：<p> 结束 → 换行，<t> 文本收集
        assert_eq!(
            cap("<root><p><t>甲</t></p><p><t>乙</t></p></root>", "t", &["p"]),
            "甲\n乙\n"
        );
        // paraTag 换行无条件（不要求文本在目标元素内 —— Go encoding/xml 同语义）
        assert_eq!(cap("<a><p>x</p><p>y</p></a>", "t", &["p"]), "\n\n");
        // 自闭合 paraTag 也插入换行（Token 展开语义，Go"自闭合段落"语料）
        assert_eq!(cap("<a><p/><t>t1</t><p></p></a>", "t", &["p"]), "\nt1\n");
        // 自闭合目标元素：无内容
        assert_eq!(cap("<t/><t>实体</t>", "t", &[]), "实体");
    }

    #[test]
    fn entities_predefined_numeric_unknown() {
        assert_eq!(cap("<a><t>a&lt;b&gt;c&amp;d</t></a>", "t", &[]), "a<b>c&d");
        assert_eq!(
            cap("<t>&quot;q&quot; &apos;s&apos;</t>", "t", &[]),
            "\"q\" 's'"
        );
        // 数字引用：十进制与十六进制（仅小写 x）
        assert_eq!(cap("<t>&#20013;&#x6587;</t>", "t", &[]), "中文");
        // 超界数字引用：字面透传
        assert_eq!(cap("<t>&#1114112;</t>", "t", &[]), "&#1114112;");
        // 未知命名实体：整体字面透传
        assert_eq!(cap("<t>a&unknown;b</t>", "t", &[]), "a&unknown;b");
        // 无分号：仅消费 '&'
        assert_eq!(cap("<t>a&b c</t>", "t", &[]), "a&b c");
    }

    #[test]
    fn cdata_and_comments_and_pi() {
        assert_eq!(
            cap("<a><t><![CDATA[<raw>&raw]]></t></a>", "t", &[]),
            "<raw>&raw"
        );
        assert_eq!(cap("<a><!-- <t>hide</t> --><t>ok</t></a>", "t", &[]), "ok");
        assert_eq!(
            cap("<?xml version=\"1.0\"?><a><t>ok</t></a>", "t", &[]),
            "ok"
        );
        assert_eq!(
            cap(
                "<!DOCTYPE a [<!ENTITY x \"<t>\">>]><a><t>ok</t></a>",
                "t",
                &[]
            ),
            "ok"
        );
    }

    #[test]
    fn crlf_normalized() {
        assert_eq!(cap("<t>a\r\nb\rc</t>", "t", &[]), "a\nb\nc");
    }

    #[test]
    fn attributes_with_angle_bracket() {
        // 引号内的 '>' 合法（属性值含 '>'）
        assert_eq!(cap("<a f=\"x>y\"><t>ok</t></a>", "t", &[]), "ok");
    }

    #[test]
    fn broken_xml_returns_collected() {
        // 未闭合标签：返回已收集内容（容错优先）
        assert_eq!(cap("<a><t>partial", "t", &[]), "partial");
        // 空栈结束标签：终止
        assert_eq!(cap("</t><t>x</t>", "t", &[]), "", "空栈结束标签即终止");
        // 非 XML 输入：空串
        assert_eq!(cap("plain text", "t", &[]), "");
        assert_eq!(cap("", "t", &[]), "");
    }

    #[test]
    fn mismatched_end_rename_chain() {
        // 错配结束标签：重命名为栈顶元素逐层闭合，内容不丢
        // <a><b><c>x</a></b></c> —— </a> 闭合 c（重命名链）
        let out = cap("<a><b><c>x</b></a></c>", "t", &[]);
        // 目标 t 不在场，仅验证不 panic 且无内容误入
        assert_eq!(out, "");
        // paraTag 的错配闭合仍应插换行
        let out = cap("<a><p>行1</b></a>", "t", &["p"]);
        assert_eq!(out, "\n", "重命名链闭合 p 插入换行；行1 不在 t 内不收集");
    }

    #[test]
    fn nested_same_tag_go_semantics() {
        // Go 语义：嵌套同名目标标签，内层结束即退出收集态
        // <t>a<t>b</t>c</t> → "ab"（c 不收集 —— encoding/xml inTag 布尔语义）
        assert_eq!(cap("<r><t>a<t>b</t>c</t></r>", "t", &[]), "ab");
    }

    #[test]
    fn eof_mid_entity_drops_node() {
        // 实体解析中途 EOF：Go 丢弃整个文本节点
        assert_eq!(cap("<t>abc&am", "t", &[]), "");
        // 无实体的 EOF 文本正常输出
        assert_eq!(cap("<t>abc", "t", &[]), "abc");
    }

    #[test]
    fn docx_extraction_real_zip() {
        let tmp = tempfile::tempdir().unwrap();
        let p = tmp.path().join("t.docx");
        write_zip(
            &p,
            &[(
                "word/document.xml",
                "<w:body><w:p><w:t>你好世界</w:t></w:p><w:p><w:t>Second</w:t></w:p></w:body>",
            )],
        );
        let out = extract_text(p.to_str().unwrap()).unwrap();
        assert_eq!(out, "你好世界\nSecond\n", "每个段落结束插换行（Go 同语义）");
    }

    #[test]
    fn pptx_slides_sorted_with_notes() {
        let tmp = tempfile::tempdir().unwrap();
        let p = tmp.path().join("t.pptx");
        write_zip(
            &p,
            &[
                ("ppt/slides/slide10.xml", "<p><a:t>B10</a:t></p>"),
                ("ppt/slides/slide2.xml", "<p><a:t>A2</a:t></p>"),
                ("ppt/notesSlides/notesSlide2.xml", "<p><a:t>N2</a:t></p>"),
                ("ppt/media/img1.png", "binary"),
            ],
        );
        let out = extract_text(p.to_str().unwrap()).unwrap();
        assert_eq!(
            out, "A2\n\nN2\n\nB10\n",
            "段落换行 + 页分隔换行（Go 同语义）"
        );
    }

    #[test]
    fn xlsx_shared_strings_and_sheets() {
        let tmp = tempfile::tempdir().unwrap();
        let p = tmp.path().join("t.xlsx");
        write_zip(
            &p,
            &[
                ("xl/sharedStrings.xml", "<sst><si><t>共享词</t></si></sst>"),
                (
                    "xl/worksheets/sheet1.xml",
                    "<ws><row><c><t>单元格</t></c></row></ws>",
                ),
            ],
        );
        let out = extract_text(p.to_str().unwrap()).unwrap();
        assert_eq!(out, "共享词\n单元格\n");
    }

    #[test]
    fn unsupported_and_missing_zip() {
        assert!(extract_text("/nonexistent.docx").is_err());
        let tmp = tempfile::tempdir().unwrap();
        let p = tmp.path().join("x.docx");
        std::fs::write(&p, b"not a zip").unwrap();
        assert!(extract_text(p.to_str().unwrap()).is_err());
        let p2 = tmp.path().join("x.rar");
        std::fs::write(&p2, b"data").unwrap();
        assert!(extract_text(p2.to_str().unwrap()).is_err());
    }

    #[test]
    fn pdf_extraction_builtin_samples() {
        // 复用 Go 版测试样张（engine/testdata 与 core/testdata 同源）；
        // extract_pdf 内置 catch_unwind：解析库 panic 也按解析失败处理
        let base = concat!(env!("CARGO_MANIFEST_DIR"), "/testdata");
        match extract_text(&format!("{base}/sample_en.pdf")) {
            Ok(t) => assert!(!t.trim().is_empty(), "英文样张应含文本"),
            Err(e) => panic!("英文样张解析失败: {e}"),
        }
        // 中文样张：两者均含 UniGB-UCS2-H Type0 CID 字体（国内办公软件导出
        // PDF 的主流形态，上游 pdf-extract 遇之直接 panic）。vendor 补丁后
        // 必须成功抽出中文全文；catch_unwind 护罩继续兜底未预期 panic
        let zh = extract_text(&format!("{base}/sample_zh.pdf"))
            .expect("UniGB-UCS2-H 中文样张必须可提取（vendor 补丁回归）");
        assert!(zh.contains("全文检索"), "中文样张应含『全文检索』: {zh}");
        let multi =
            extract_text(&format!("{base}/sample_multi.pdf")).expect("多页中文样张必须可提取");
        assert!(
            multi.contains("多页文档"),
            "多页样张应含『多页文档』: {multi}"
        );
    }

    /// 把一组 (name, content) 写成 zip 文件。
    fn write_zip(path: &std::path::Path, entries: &[(&str, &str)]) {
        use std::io::Write;
        let f = std::fs::File::create(path).unwrap();
        let mut zw = zip::ZipWriter::new(f);
        for (name, content) in entries {
            zw.start_file(*name, zip::write::SimpleFileOptions::default())
                .unwrap();
            zw.write_all(content.as_bytes()).unwrap();
        }
        zw.finish().unwrap();
    }

    #[test]
    fn slide_num_parsing() {
        assert_eq!(slide_num("ppt/slides/slide3.xml"), 3);
        assert_eq!(slide_num("ppt/notesSlides/notesSlide10.xml"), 10);
        assert_eq!(slide_num("ppt/slides/slide.xml"), 0);
    }
}
