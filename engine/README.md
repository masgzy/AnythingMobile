# AnythingMobile Rust 引擎（alpha20 迁移）

替代 `core/` Go 引擎（bleve+gse / gomobile AAR）的 Rust FFI 实现。
迁移动因：Go runtime 体积下限 ~10MB/ABI（arm64 APK 14.47MB、universal 50.70MB），
Rust 无 runtime，目标 arm64 8~10MB、universal 20~26MB；JNI 直连微秒级无拷贝。

## 构建

```bash
# 桌面测试（CI 冒烟，秒级）
cargo test

# Android .so（需 NDK，输出到 app/src/main/jniLibs/）
./build-so.sh
# 或指定输出目录（CI 冒烟防污染 app 打包）
./build-so.sh dist/
```

产物：`libanything.so`（arm64-v8a / armeabi-v7a / x86_64，16KB 页对齐）。

## 与 Go 版的契约

- JSON 字段名与 `core/` 包结构体 tag 严格一致（`types.rs`）；
- 方法签名与 gomobile 生成 API 同名对应（`RustEngine.kt` ↔ `jni_bridge.rs`）；
- 错误统一 Java `RuntimeException`（gomobile 时代 error→throws Exception 等价物）；
- panic 永不出 FFI 边界（catch_unwind 护罩 + 保持 unwind）。

## 迁移阶段

| 阶段 | 内容 | 状态 |
|------|------|------|
| ① | crate 骨架 + JNI 桥 + build-so.sh + CI | 本提交 |
| ② | tantivy + jieba-rs（名称/全文索引、搜索语法、持久化） | 待做 |
| ③ | 文档解析（OOXML/CFB/PDF） | 待做 |
| ④ | Kotlin 宿主切换 + 发布 alpha20 | 待做 |

## 目录

```
engine/
├── Cargo.toml / .cargo/config.toml   # opt-level="s" + fat LTO + strip + 16KB 对齐
├── build-so.sh                        # cargo-ndk 三 ABI 构建 + 对齐校验
├── src/
│   ├── lib.rs          # 模块划分 + ABI 版本
│   ├── types.rs        # JSON 契约（与 Go tag 一一对应）
│   ├── error.rs        # "core: ..." 前缀错误
│   ├── engine.rs       # 状态机（扫描/取消/统计/回调）
│   ├── walker.rs       # 全深度并行遍历（Go dirWalker 同款）
│   ├── callback.rs     # Rust→Kotlin 回调（Listener/ExternalParser）
│   ├── registry.rs     # 句柄表（jlong → Arc<EngineImpl>）
│   └── jni_bridge.rs   # JNI 导出（Java_com_masgzy_anything_core_RustEngine_*）
└── tests/              # 契约与冒烟测试
```
