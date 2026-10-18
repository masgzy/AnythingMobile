//! AnythingMobile 搜索索引引擎（Rust 端）。
//!
//! alpha20 迁移：替代 `core/` 下的 Go 引擎（bleve+gse，经 gomobile bind
//! 产出 AAR）。迁移动因与分阶段计划见 `docs/architecture.md`：
//! Go runtime 体积下限 ~10MB/ABI，Rust 无 runtime，目标 arm64 APK
//! 14.47MB → 8~10MB。
//!
//! ## 模块划分
//! - `types`：JSON 契约类型（字段名与 Go 版一一对应，宿主无感知切换）
//! - `error`：统一错误类型（`"core: ..."` 前缀与 Go 版报错文案对齐）
//! - `engine`：引擎状态机（扫描 / 索引 / 搜索 / 注入 / 外部通道 / 统计）
//! - `walker`：全深度并行目录遍历（移植 Go `walker.go` 的剪枝与并发模型）
//! - `nameindex`：2-gram 倒排名称索引（移植 Go `nameindex.go`）
//! - `fts`：tantivy 全文内容库（移植 Go `fts.go` + `ftsquery.go`）
//! - `segment`：jieba 分词与 unigram+bigram 分词器（移植 Go `ftsanalyzer.go`）
//! - `query`：M4 搜索语法 type:/dir:（移植 Go `query.go`）
//! - `textutil`：文本清洗/归一化/小写化（移植 Go `textutil.go`）
//! - `docparse`：文档正文抽取 OOXML/PDF（移植 Go `docparse.go` + `pdfparse.go`）
//! - `persist`：快照持久化 v4（移植 Go `persist.go`）
//! - `parsequeue`：文档解析流水线（移植 Go `parsequeue.go`）
//! - `callback`：Rust→Kotlin 回调（ProgressListener / ExternalParser）
//! - `registry`：引擎句柄注册表（jlong handle → Arc<EngineImpl>）
//! - `jni_bridge`：全部 JNI 导出（`Java_com_masgzy_anything_core_RustEngine_*`）
//!
//! ## FFI 安全铁律（对齐 CFSTMobile 实测结论）
//! - panic 策略保持 unwind，所有 JNI 导出经 `catch_unwind` 包裹，
//!   任何内部 panic 转为 Java `RuntimeException`，绝不杀死宿主进程；
//! - 字符串协议与 Go/gobind 时代一致：复合参数与返回值一律 JSON 字符串；
//! - Kotlin 侧符号契约见 `app/src/main/java/com/masgzy/anything/rust/RustEngine.kt`，
//!   类路径或方法名变更必须同步 `jni_bridge.rs`，否则运行时 `UnsatisfiedLinkError`。
//!
//! ## 迁移阶段（本文件为阶段①骨架）
//! ① crate 骨架 + JNI 桥 + build-so.sh + CI（本阶段：遍历/进度/取消/统计为真，
//!    索引与检索尚未接入）
//! ② tantivy + jieba-rs 接入（名称索引 / 全文索引 / 搜索语法 / 持久化快照）
//! ③ 文档解析（OOXML/CFB/PDF）
//! ④ Kotlin 宿主切换 + 发布 alpha20

// 阶段②③完成后的占位清理：全部模块已接线，如有个别预留字段再单独放开。
#![allow(dead_code)]

pub mod callback;
pub mod docparse;
pub mod engine;
pub mod error;
pub mod fts;
pub mod jni_bridge;
pub mod nameindex;
pub mod parsequeue;
pub mod persist;
pub mod query;
pub mod registry;
pub mod segment;
pub mod textutil;
pub mod types;
pub mod walker;

/// FFI ABI 版本：JSON 契约或方法签名发生不兼容变更时 +1。
/// Kotlin 侧启动时可校验（阶段④接入），不一致即提示重装/反馈。
pub const ABI_VERSION: i32 = 1;

/// 展示用完整版本串，例如 `0.1.0-alpha.1 (rust, abi=1)`。
pub fn engine_version() -> String {
    format!("{} (rust, abi={})", env!("CARGO_PKG_VERSION"), ABI_VERSION)
}

/// JVM 单例缓存：`System.loadLibrary` 触发 `JNI_OnLoad` 时登记。
/// 宿主回调（进度/解析）据此 attach 线程；桌面 host 测试无 JVM，
/// 回调自动退化为 no-op，不影响 `cargo test`。
pub(crate) fn jvm() -> Option<&'static jni::JavaVM> {
    jni_bridge::cached_jvm()
}
