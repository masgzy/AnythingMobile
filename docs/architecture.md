# AnythingMobile 架构设计

## 1. 总体架构

三端分工：

```
Kotlin 外壳 —— 只做"壳"：UI、系统权限、磁贴、分享、兜底文档解析
        │
        │  JNI（jni crate：句柄式 API + JSON 协议）
        ▼
Rust 核心引擎 engine/ —— 只做"重活"：遍历、索引、检索、文本抽取
        │   名称/目录索引：2-gram 倒排（自研，src/nameindex.rs）
        │   全文索引：tantivy + jieba-rs（src/fts.rs + src/segment.rs）
        │   文档抽取：zip + 手写 XML 扫描器 + pdf-extract（src/docparse.rs）
        ▼
文件系统（共享存储 / SD 卡 / OTG）
```

原则：**凡是数据量大的循环都在 Rust 侧完成**，Kotlin 与 Rust 之间只传递
JSON 字符串与进度回调，把跨语言往返次数压到最低；FFI 边界全部
`catch_unwind` 护罩，任何内部 panic 都不会杀死宿主进程。

## 2. JNI 契约（对应 gobind 时代约束）

gobind（Go）→ JNI（Rust）迁移后，跨语言协议的形状保持不变：

1. **受支持类型**：JNI 原生支持基本类型与对象；本项目的复合参数
   与返回值延续 JSON 字符串协议（宿主解析代码零改动）：
   - `startScan(handle, optionsJson)`：`{"roots":["/storage/emulated/0"],"mode":"full"}`
   - `search(handle, q, limit)`：返回 SearchResponse JSON
2. **句柄生命周期**：`newEngine(workers, dataDir)` 返回 `jlong` 句柄
   （0 = 失败且伴随 Java 异常），所有方法以句柄首参寻址；
   `close(handle)` 后句柄失效，再访问抛「引擎句柄无效或已关闭」。
   Rust 侧全局注册表（`registry.rs`）持有 `Arc<EngineImpl>`，
   调用期间不持锁。
3. **命名映射**：与 gobind 时代一致（`startScan`/`onProgress` 等 lowerCamelCase），
   Kotlin 契约类 `app/.../rust/RustEngine.kt` 与 Rust 侧
   `jni_bridge.rs` 符号一一对应，任何一侧变更必须双侧同步。
4. **整数宽度**：时间戳/大小/句柄一律 `Long`。
5. **panic 严禁跨界**：全部 16 个导出经 `catch_unwind` 包裹，
   内部 panic 转为 Java `RuntimeException`；Cargo profile 保持 unwind
   （**禁改 panic=abort**）。Rust→宿主回调（ProgressListener /
   ExternalParser）失败时吞掉 Java 异常，绝不向上传播。
6. **错误传递**：失败抛 `RuntimeException`（gomobile 时代
   `error`→`throws Exception` 的等价物）。
7. **ABI 版本**：`ABI_VERSION` 常量锁定 JSON 契约与符号表版本，
   Kotlin 侧启动时校验（不匹配即拒绝启动，提示重装）。

### 绑定 API 速查（Kotlin，`RustEngine` object）

| Kotlin | 说明 |
|--------|------|
| `RustEngine.abiVersion()` / `engineVersion()` | ABI 校验 / 版本串（设置页展示） |
| `newEngine(0, dir): Long` | 创建引擎（同步恢复快照，"秒开"） |
| `setListener(h, l)` / `setExternalParser(h, p)` | 回调注册（可随时替换） |
| `startScan(h, json)` / `cancelScan(h)` / `isScanning(h)` | 扫描控制 |
| `search(h, q, 100)` | 三类命中合并（支持 `type:` `dir:` 语法） |
| `removePaths(h, json)` / `addDocumentText(h, p, t)` | 宿主侧变更通道 |
| `ingest(h, json)` / `extractDoc(h, name, bytes)` | SAF/DocumentsProvider 通道 |
| `replaceExternalEntries(h, feedJson)` | Shizuku/Stellar 外部条目全量替换 |
| `stats(h)` / `close(h)` | 统计 / 释放（进程内通常无需调用） |

## 3. 数据结构与流程

### 扫描流程

```
startScan(optionsJson)
  └─ 扫描线程: traverse(roots)
       ├─ 每个 root 列目录，第一层子目录 = 种子
       ├─ 并发上限 = workers（默认 CPU 核数，≤8）
       ├─ walkDir 子树：
       │    ├─ 目录过滤：.开头 / LOST.DIR / Android/{data,obb}
       │    ├─ 批量入 NameIndex（每目录一次写锁，锁竞争摊薄一个数量级）
       │    └─ 文档（≤20MB）→ 入队解析流水线（独立 worker 池攒批 32 条提交）
       │       变更文档的旧全文删除也在本阶段攒批（单次 commit，alpha26）
       ├─ 遍历结束 → 等待解析流水线排空
       └─ finishScan: 增量 RemoveExcept → 异步快照落盘（记录导出也在
          落盘线程内，alpha26）→ OnFinished(statsJSON)
```

索引范围（alpha26 三模式，宿主侧 AppViewModel 决定 roots）：

- **仅全盘**：roots = 共享存储根（历史默认语义）；
- **仅目录**：roots = 已授权目录的物理路径；不可映射时走 SAF 注入；
- **全盘+目录**：全盘扫描之外，「同步索引」对目录清单中不可物理映射的
  DocumentsProvider 虚拟目录（部分应用的文件提供器只经此暴露内容）
  逐个 SAF 遍历 + Ingest 注入；Android/data 特权条目（alpha26 自设置页
  迁入「目录」页）经 Shizuku/Stellar 枚举后走 replaceExternalEntries。

### 搜索流程

```
search(q, limit)
  ├─ parseQuery：剥离 type:/dir: 过滤器（别名归一/引号值/去重）
  ├─ NameIndex.searchAll：查询词 bigram 倒排表交集 → 候选子串验证
  │    排序：名字短优先 → mtime 新优先
  ├─ ContentStore.searchAll：bigram 倒排收敛候选 → 预评分排序 →
  │    存储文本子串校验 + 摘要（词边界整词命中 ×4 加分）
  ├─ 外部扩展索引（extNames/extDirs）同口径搜索
  └─ 两轮配额合并（文件名 50% / 全文 25% / 目录 15% / 外部 10%，
      剩余额度按 文件名→全文→目录→外部 填满）→ SearchResponse JSON
```

### NameIndex 设计要点（`src/nameindex.rs`）

- **字符级 2-gram 倒排**：天然支持中英文混合子串查询，
  与 Everything 的思路一致（Everything 用 NTFS USN + 后缀树，
  移动端无 USN，故用倒排代替）。
- 单字符查询退化为线性扫描（十万级文件名仍然毫秒级）。
- 同路径重复 Add 会先删旧记录，天然支持增量重扫；
  `byPath` 提供 O(1) 路径映射支撑 `LookupBatch`/`RemoveExcept`。
- 内存态索引（不落 tantivy）：十万条目约 10~20MB，快照
  （JSON + gzip level 1，原子重命名）负责持久化。

### ContentStore（tantivy + jieba，`src/fts.rs` + `src/segment.rs`）

全文索引采用 **tantivy**（mmap 持久化段格式）+ **jieba-rs**
（词典与 Go 时代同源：`core/data/dict_zh_min.txt` → `engine/data/dict_zh_min.txt.gz`，
11 万词条，分词口径不变）。

字段设计（Go 时代逐字保留）：

| 字段 | 分词 | 用途 |
|------|------|------|
| `path` | raw（整串单 token） | 文档唯一键：删除依据 + 启动时词枚举重建 idSet |
| `big` | 字符级 unigram+bigram（小写） | 候选集收敛（各 bigram 倒排求交），只存词频不存位置 |
| `words` | jieba 中文分词 | 词边界相关性加权（整词命中 ×4 加分） |
| `text` | 不索引，仅存储（LZ4 压缩） | 精确校验与命中摘要的文本来源 |

搜索语义：连续词保持"精确子串"（bigram 交集收敛候选 → 预评分排序
→ 读取存储文本 `find` 校验，无假阳性）；空白分词后多词 AND；单字直查
unigram。候选集验证按预评分降序消费、凑满预算提前收工；≤2 rune 词的
候选集即精确命中（total 恒精确），≥3 rune 长词存在跨 bigram 假阳性，
以验证后计数为准（下界）。

持久化与一致性：tantivy 写线程攒批提交（解析流水线 32 条/批），
进程崩溃丢失的最后一批文档由增量扫描的"名称在而全文缺失 → 补解析"
机制自愈；快照 v4（Rust JSON 格式）只保存名称/目录/外部条目
（KB 级），旧 Go 快照（v3 gob）解析失败即静默放弃 → 全量重建。
tantivy 独占锁由 writer 创建阶段获取 —— 同目录双开会创建失败降级
而非挂死，Kotlin 侧以进程级单例杜绝二次创建。

小写化对齐：Rust `char::to_lowercase()` 是完整大小写转换（`İ` →
"i̇" 两字符），与 Go `strings.ToLower` 的单字符简单映射不同；
索引与查询两端统一走 `textutil::lower_simple`（取映射首字符），
保证与 Go 时代索引产物逐字节兼容的分词口径。

## 4. 文档正文抽取

| 格式 | 方案 | 阶段 |
|------|------|------|
| .docx / .pptx / .xlsx | zip 抽取 + **手写 XML 字节流扫描器直译移植**（`src/docparse.rs`，Go 版逐语义：重命名链/嵌套同名标签/实体跨界/EOF 丢节点），零 XML 三方依赖 | ✅ M1 → Rust alpha20 |
| .pdf | pdf-extract（纯 Rust，含 WinAnsi/CMap/Type0 CID 字体支持）；整体 `catch_unwind` 护罩，损坏文档最多损失自身 | ✅ alpha14 → Rust alpha20 |
| .doc / .ppt / .xls / .wps | **Kotlin 层自研解析器**（`data/office/`：CFB 容器 + Word 词片表 + Excel SST + PPT 记录树，零三方依赖），经 `ExternalParser.extractText()` 回调注入引擎 —— 优于 POI 方案（+0MB 依赖） | ✅ alpha13 |

> ExternalParser 为可选注入：未注册时引擎照常工作，只是旧格式
> 文档不参与全文索引（文件名搜索不受影响）。

## 5. Android 权限策略

依据官方文档 `training/data-storage/manage-all-files`：

- API 30+：manifest 声明 `MANAGE_EXTERNAL_STORAGE`，
  `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` 引导用户到系统设置页，
  `Environment.isExternalStorageManager()` 检测。
  "设备上的文件搜索"属官方明示的正当使用场景。
- API 24~29：传统 `READ/WRITE_EXTERNAL_STORAGE` 运行时权限。
- 即便持有全盘权限，**其他应用的 `Android/{data,obb}` 仍不可访问**，
  遍历器直接过滤这两个目录。
- 本项目开源且不上架 Google Play，无 2025-11 起 16KB 对齐的商店审核问题，
  但 **16KB 页设备（部分旗舰已出厂启用）加载未对齐 .so 会直接崩溃**，
  因此 `engine/.cargo/config.toml` 为各 android target 固定携带
  `-C link-arg=-Wl,-z,max-page-size=16384`，`build-so.sh` 构建后用
  llvm-readelf 逐一校验。

## 6. CI

`.github/workflows/ci.yml`：

1. `cargo fmt --check` + `cargo clippy --all-targets -- -D warnings` + `cargo test`
   （Rust 引擎 121 用例，无需安卓环境；vendor/pdf-extract 的 lint 由
   cargo 的 cap-lints 机制自动豁免）
2. `./engine/build-so.sh`（cargo-ndk 三 ABI 交叉编译 + 16KB 对齐校验，
   .so 直出 `app/src/main/jniLibs/<abi>/`）
3. `./gradlew :app:testDebugUnitTest` + 双渠道 release 构建
   （`:app:assembleStandardRelease :app:assembleOfflineRelease`，alpha26）
4. 产物上传：standard + offline 各多 ABI + universal 签名 APK（8 份单文件直传，
   offline 包文件名带 `_offline` 后缀）

`emulator.yml`：模拟器冒烟 + 仪器化测试（JNI 加载/引擎构造/扫描链路）；
`release.yml`：tag 触发，Rust 测试把关后构建双渠道 APK 并发布
（offline 包名 `AnythingMobile-<版本>-<abi>_offline.apk`）。

## 7. 里程碑

| 里程碑 | 内容 | 状态 |
|--------|------|------|
| M1 | 骨架：引擎 API/遍历/文件名索引/OOXML 抽取/外壳 UI/CI | ✅ |
| M2 | 真机联调、增量重扫节流（alpha10/11）、旧格式接入决策（自研替代 POI）、旧格式接入（alpha13） | ✅ |
| M3 | 持久化全文索引、PDF 抽取、索引落盘与启动恢复 | ✅ alpha14 |
| M4 | 搜索语法（`type:` `dir:` 过滤）、体验打磨、发布渠道 | 语法 ✅ alpha14；F-Droid ⬜ |
| M5 | Rust FFI 迁移：引擎整体换 Rust（tantivy + jieba），体积/内存双降 | ✅ alpha20 |

## 8. 许可

- 本项目：GPL-3.0（v1.0.0-alpha16 起重新开源并重置仓库历史）。
- 原版 Anything（SumStudio）：闭源，本项目未使用其任何代码，仅致敬其产品思路。

## 9. Rust 引擎迁移（alpha20 完成）

Go 引擎的两项硬约束推动迁移：Go runtime 体积下限 ~10MB/ABI（arm64 APK 14.47MB、
universal 50.70MB）；goroutine+bleve 在低端机上的索引期内存峰值偏高。
参照同类项目 CFSTMobile（Rust 引擎，universal 6.31MB）验证了路线可行性。

```
Kotlin 外壳 —— UI/权限/SAF/磁贴/兜底解析（不变）
        │
        │  JNI（jni crate + JSON 协议，替代 gobind 生成的桥）
        ▼
Rust 引擎 engine/ —— 遍历/索引/检索/文本抽取
        │   名称/目录：2-gram 倒排（自研 nameindex.rs，内存态 + 快照）
        │   全文：tantivy + jieba-rs（替代 bleve + gse，词典同源）
        │   解析：zip + 手写 XML 扫描器 + pdf-extract（legacy 格式仍走 Kotlin 兜底）
        ▼
文件系统（共享存储 / SD 卡 / OTG）
```

### 契约不变量（迁移全程有效）

1. JSON 字段名与 Go 结构体 tag 严格一致（`engine/src/types.rs` 契约注释）；
2. JNI 符号与 `app/.../rust/RustEngine.kt` 双侧同步，`ABI_VERSION` 锁契约版本；
3. panic 不出 FFI 边界：全部导出经 catch_unwind，Cargo profile 保持 unwind
   （**禁改 panic=abort**，否则任何内部 panic 直接杀死 App 进程）；
4. 错误统一抛 Java `RuntimeException`（gomobile 时代 error→throws Exception 等价物）；
5. `.so` 统一 16KB 页对齐（`engine/.cargo/config.toml` 的
   `-Wl,-z,max-page-size=16384`，对齐 Go AAR 的既有要求）。

### 迁移结果（实测）

| 指标 | Go（alpha19） | Rust（alpha20，实测） |
|------|---------------|-----------------|
| .so 单 ABI（arm64） | ~10MB（AAR 内） | 7.37MB（APK 内未压缩存储） |
| arm64 APK | 14.47MB | **8.70MB（-40%）** |
| universal APK | 50.70MB | **21.44MB（-58%）** |
| armeabi-v7a / x86_64 APK | — | 6.69MB / 9.31MB |
| JNI 符号 / Kotlin 契约 | gobind 生成 | 16 导出 1:1 手写契约 |
| 引擎测试 | go test | 119 个 Rust 用例全绿 |

### 历史阶段记录

| 阶段 | 内容 | 状态 |
|------|------|------|
| ① | `engine/` crate 骨架 + JNI 桥 16 导出 + build-so.sh + CI rust job | ✅ |
| ② | tantivy + jieba-rs：名称/全文索引、type:/dir: 语法、快照持久化 v4 | ✅ |
| ③ | 文档解析：OOXML（手写扫描器直译）/PDF（pdf-extract）；legacy 格式维持 Kotlin 兜底 | ✅ |
| ④ | Kotlin 宿主切换（EngineRepository 换绑）+ 删除 Go 引擎 + 发布 alpha20 | ✅ |

### 构建与验证

```bash
cd engine
cargo test          # 桌面冒烟（CI 同款：fmt/clippy 严格/test）
./build-so.sh       # cargo-ndk 三 ABI → app/src/main/jniLibs/（16KB 校验内置）
```

CI（ci.yml）：单 job 串起「Rust 测试 → .so 构建 → Gradle 打包」，
engine 依赖经 Swatinem/rust-cache 缓存，无 Cargo.lock 变更时秒级恢复。
