# 贡献指南

感谢关注 AnythingMobile！这是一个单人维护的 alpha 阶段项目，欢迎
issue 与 PR。

## 项目结构

```
engine/          Rust 引擎（2-gram 名称索引 + tantivy 全文 + JNI 导出）
  src/           引擎源码（模块地图见 engine/README.md）
  vendor/        补丁版 pdf-extract（改动溯源见 vendor/VENDOR-NOTES.md）
app/             Kotlin Compose 壳（UI、SAF/Shizuku 通道、更新检查）
docs/            architecture.md —— 架构与阶段史
```

分层铁律：**Kotlin 只做壳、Rust 做重活、跨界只传 JSON**。JNI 导出
全部是协议转换，业务逻辑不留在桥接层。

## 开发环境

- JDK 17（Temurin）、Android SDK（AGP 9 / Gradle 9.7.1，走 wrapper）
- Rust stable + `cargo-ndk`，Android NDK（引擎交叉编译，见
  `engine/build-so.sh`）
- 桌面跑测试不需要 NDK：`cd engine && cargo test`

## 提交前自查

Rust 侧三连门禁（CI 同款，任一不过不会合并）：

```bash
cd engine
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test
```

Kotlin 侧：

```bash
./gradlew :app:testStandardDebugUnitTest :app:lintStandardDebug
```

涉及引擎契约（JSON 字段、JNI 导出签名）的改动必须同步：

- `docs/architecture.md` 的协议部分
- `engine/README.md` 的模块地图
- 若动了快照/索引存储格式，`ABI_VERSION` / 快照版本号需要递增，
  并在 CHANGELOG.md 标注"升级需重建索引"

## 约定

- 注释解释"为什么"而不是"是什么"；不直观的决策（阈值、兼容逻辑、
  平台怪癖）请注明来源（真机实证 / Go 原版语义 / 官方文档）。
- 提交信息用中文，格式 `类型(范围): 摘要`，类型取
  feat / fix / perf / refactor / docs / ci / art。
- 安全相关改动（解析器、路径校验、权限）请在 PR 描述里说明威胁模型
  与防御层次，参考 `docs/architecture.md` 的威胁模型一节。

## 报告问题

- 搜索类：请附查询词、文件特征（文件名/格式）、应用版本（设置页可见）。
- 崩溃类：设置页"分享日志"导出的报告已含堆栈与本应用运行日志。
- 安全漏洞：**请勿公开 issue**，通过 GitHub Security Advisories
  私密报告。

## 许可

贡献即同意以 GPL-3.0 许可提交代码。
