# pdf-extract vendor 说明

- **上游**：https://github.com/jrmuizel/pdf-extract ，版本 **0.12.1**（crates.io 原样源码 + 下述最小补丁），MIT 许可（见 Cargo.toml `license` 字段，与本项目 GPL-3.0 兼容）。
- **接管方式**：`engine/Cargo.toml` 末尾 `[patch.crates-io] pdf-extract = { path = "vendor/pdf-extract" }`。engine 是独立 cargo workspace 根，patch 生效。

## 为什么要 vendor（盲区背景）

上游 `src/lib.rs` 的 `PdfCIDFont::new` 对 Type0 字体命名编码只认 `Identity-H/V`，
其余一律 `panic!("unsupported encoding ...")`。而国内办公软件（WPS / Word 的
中文导出）生成的 PDF 大量使用**预定义 CID 字体 + `UniGB-UCS2-H` 编码**
（GBK/GB2312 文档的主流形态），文本抽取必触发 panic。

引擎侧 `docparse::extract_pdf` 的 `catch_unwind` 护罩能拦住 panic（App 不崩，
索引不中断），但该文档全文提取失败 → 只能按文件名搜，全文搜不到。

## 补丁内容（4 处，全部带 `AnythingMobile vendor patch` 注释标记）

`UniGB/UniJIS/UniKS/UniCNS-UCS2-H/-V` 家族的字符码**本身就是 UCS-2 码点**
（CMap 名中的 UCS2 即此含义），无需查 Adobe CMap 表即可正确解码：

1. `struct PdfCIDFont` 增加 `identity_ucs2: bool` 标志；
2. `PdfCIDFont::new` 编码匹配：`Uni*` 且含 `UCS2` 的命名 CMap 按与
   `Identity-H` 相同的 2 字节恒等 ByteMapping 处理并置位标志；
3. 构造函数透传该标志；
4. `PdfCIDFont::decode_char`：ToUnicode 缺失/缺项时，若标志置位则按
   `CID == UCS-2 码点` 恒等回退（代理区不映射，与上游 ToUnicode 解析行为
   一致；ToUnicode 存在时仍优先，语义不变）。

非 UCS2 家族的未知编码仍走原 panic 路径（引擎护罩兜底），补丁不扩大解码范围。

## 回归样张

`engine/testdata/sample_zh.pdf`、`sample_multi.pdf`（UniGB-UCS2-H Type0）在
`docparse.rs::pdf_extraction_builtin_samples` 中已从「允许失败」强化为
「必须成功且含中文」。

## 升级上游时

把新版本源码覆盖本目录后，按上述 4 处标记重新套补丁；若上游已原生支持
UCS2 家族（或换成返回 `Result` 的错误处理），可直接删掉 vendor 回归 crates.io。
