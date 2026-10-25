# AnythingMobile Rust 引擎（alpha20 迁移，alpha26 迭代中）

替代 Go 引擎（bleve+gse / gomobile AAR，已于 alpha20 移除）的 Rust FFI 实现。
迁移动因：Go runtime 体积下限 ~10MB/ABI（arm64 APK 14.47MB、universal 50.70MB），
Rust 无 runtime；JNI 直连微秒级；索引期内存显著下降。

## 构建

```bash
# 桌面测试（CI 同款：fmt + clippy 严格 + 121 用例）
cargo test

# Android .so（需 NDK，输出到 app/src/main/jniLibs/）
./build-so.sh
# 或指定输出目录（CI 冒烟防污染 app 打包）
./build-so.sh dist/
```

产物：`libanything.so`（arm64-v8a / armeabi-v7a / x86_64，16KB 页对齐）。
实测体积：arm64 7.37MB（alpha20）→ 7.06MB（alpha25，APK 内不压缩存储直载）；
alpha20 发布实测：arm64 APK 8.70MB（alpha19 为 14.47MB，-40%）、
universal 21.44MB（alpha19 为 50.70MB，-58%）。

## 与宿主的契约

- JSON 字段名与 Go 时代结构体 tag 严格一致（`types.rs` 契约注释，宿主解析零改动）；
- JNI 符号 `Java_com_masgzy_anything_rust_RustEngine_*` 与
  `app/.../rust/RustEngine.kt` 双侧同步（`ABI_VERSION` 锁版本）；
- 错误统一抛 Java `RuntimeException`（gomobile 时代 error→throws Exception 等价物）；
- panic 永不出 FFI 边界（catch_unwind 护罩 + Cargo profile 保持 unwind，
  **禁改 panic=abort**）；
- 回调（ProgressListener / ExternalParser）失败时吞掉 Java 异常，
  等价 Go 版 safeCall recover。

## 模块地图

```
engine/
├── Cargo.toml / .cargo/config.toml   # opt-level="s" + fat LTO + strip + 16KB 对齐
│                                      # [patch.crates-io] pdf-extract → vendor/ 本地补丁
├── build-so.sh                        # cargo-ndk 三 ABI 构建 + llvm-readelf 对齐校验
├── data/dict_zh_min.txt.gz            # jieba 词典（与 Go 时代 core/data 同源，gzip 内嵌）
├── testdata/*.pdf                     # PDF 解析样张（与 Go 时代同源）
├── vendor/pdf-extract/                # pdf-extract 0.12.1 本地补丁版（alpha24）：
│                                      #   UniGB-UCS2 家族 CMap 中文盲区修复（详见 VENDOR-NOTES.md）
└── src/
    ├── lib.rs          # 模块划分 + ABI 版本 + 版本串
    ├── types.rs        # JSON 契约（与 Go tag 一一对应）
    ├── error.rs        # "core: ..." 前缀错误
    ├── engine.rs       # 状态机（扫描/索引/搜索/注入/外部通道/统计/搜索缓存）
    ├── walker.rs       # 全深度并行遍历（批次钩子，Go dirWalker 同款）
    ├── nameindex.rs    # 2-gram 倒排名称索引（byPath O(1)/RemoveExcept/LookupBatch）
    ├── fts.rs          # tantivy 全文内容库（三字段设计 + 查询三阶段 + 攒批 add/remove）
    ├── segment.rs      # jieba 单例 + unigram+bigram 分词器
    ├── query.rs        # M4 搜索语法 type:/dir:（别名/引号值/过滤）
    ├── textutil.rs     # 清洗/归一化/Go 同款单字符小写映射
    ├── docparse.rs     # OOXML（手写 XML 扫描器直译）+ PDF（vendor pdf-extract）
    ├── persist.rs      # 快照 v4（JSON+gzip 原子落盘）+ SaveGate 落盘串行化
    │                   #   （alpha26：记录导出一并移入落盘线程）
    ├── parsequeue.rs   # 解析流水线（worker 池攒批 32 条提交）
    ├── callback.rs     # Rust→Kotlin 回调（Listener/ExternalParser）
    ├── registry.rs     # 句柄表（jlong → Arc<EngineImpl>，不持锁调用）
    └── jni_bridge.rs   # JNI 导出（Java_com_masgzy_anything_rust_RustEngine_*）
```

## 索引格式与升级

- 快照 `index.snap`（JSON v4）：名称/目录/外部条目；损坏或版本不符静默放弃，
  由全量扫描兜底；旧 Go 快照（v3 gob）同路径解析失败 → 自动全量重建；
- 全文库 `fts/`：tantivy 段格式；打开失败（含旧 bleve 目录混入）先删后建一次，
  再失败降级（broken：搜索空、写入报错，绝不挂死）。
