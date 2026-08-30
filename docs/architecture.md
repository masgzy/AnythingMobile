# AnythingMobile 架构设计

## 1. 总体架构

三端分工：

```
Kotlin 外壳 —— 只做"壳"：UI、系统权限、磁贴、分享、兜底文档解析
        │
        │  gomobile bind 产出的 AAR（JNI 桥由工具自动生成）
        ▼
Go 核心引擎 —— 只做"重活"：遍历、索引、检索、文本抽取
        │
        ▼
文件系统（共享存储 / SD 卡 / OTG）
```

原则：**凡是数据量大的循环都在 Go 侧完成**，Kotlin 与 Go 之间只传递
JSON 字符串与进度回调，规避 gobind 的类型限制与调用开销。

## 2. gobind 约束（官方文档摘要）

来源：`golang.org/x/mobile/cmd/gobind` 文档与 Go Mobile Wiki。

1. **受支持类型**：有符号整数、浮点、string、bool、[]byte（引用传递）、
   参数与返回值全部受支持的函数类型、方法全部受支持的接口/结构体。
   `[]string` 等切片**不在文档承诺范围内** —— 因此本项目的复合参数
   与返回值一律使用 JSON 字符串：
   - `StartScan(rootsJSON string) error`：`[\"/storage/emulated/0\"]`
   - `Search(query string, limit int64) (string, error)`：返回 SearchResponse JSON
2. **构造器约定**：`NewT(...) *T` 形式的包级函数会被转换为 T 的构造器，
   因此 `NewEngine(workers int) (*Engine, error)` 在 Java/Kotlin 侧
   既是 `Core.newEngine(...)` 也是 `Engine(...)`。
3. **命名映射**：方法名转 lowerCamelCase（`StartScan`→`startScan`、
   `OnProgress`→`onProgress`）；包级函数挂载于包名 TitleCase 的抽象类
   （包 `core` → `Core`）。
4. **整数宽度**：Go `int` 映射为 Java `long`；跨语言 API 显式使用 `int64`。
5. **panic 严禁跨界**：panic 跨越语言边界会导致进程退出。
   所有 Go→宿主回调（ProgressListener / ExternalParser）均有 recover 防护。
6. **错误传递**：返回值末位的 `error` 映射为 Java `throws Exception`。
7. **构建脚本**：Go 1.16+ 需在 bind 前执行
   `go get -d golang.org/x/mobile/cmd/gomobile` 保留 indirect 依赖（Wiki 建议）。

### 绑定 API 速查（Go → Kotlin）

| Go | Kotlin |
|----|--------|
| `NewEngine(0)` | `Engine(0)`（抛异常需捕获） |
| `SetListener(l)` | `engine.setListener(l)` |
| `SetExternalParser(p)` | `engine.setExternalParser(p)` |
| `StartScan(json)` | `engine.startScan(json)` |
| `CancelScan()` | `engine.cancelScan()` |
| `IsScanning()` | `engine.isScanning()` |
| `Search(q, 100)` | `engine.search(q, 100)` |
| `AddDocumentText(p, t)` | `engine.addDocumentText(p, t)` |
| `Stats()` | `engine.stats()` |
| `ProgressListener` | `object : ProgressListener { onProgress/onFinished/onError }` |
| `ExternalParser` | `object : ExternalParser { extractText(p) }` |

## 3. 数据结构与流程

### 扫描流程

```
StartScan(rootsJSON)
  └─ goroutine: traverse(roots)
       ├─ 每个 root 列目录，第一层子目录 = 种子
       ├─ 种子并发上限 = workers（默认 CPU 核数，≤8）
       ├─ WalkDir 子树：
       │    ├─ 目录过滤：.开头 / LOST.DIR / Android/{data,obb}
       │    ├─ 文件 → NameIndex.Add（2-gram 倒排）
       │    └─ 文档（≤20MB）→ ExtractText → ContentStore.Add
       └─ 结束 → OnFinished(statsJSON)
```

### 搜索流程

```
Search(q, limit)
  ├─ NameIndex.Search：查询词 bigram 倒排表交集 → 候选子串验证
  │    排序：名字短优先 → mtime 新优先
  ├─ ContentStore.Search：小写子串匹配 → 摘要（M3 换 bleve）
  └─ 合并 → SearchResponse JSON
```

### NameIndex 设计要点

- **字符级 2-gram 倒排**：天然支持中英文混合子串查询，
  与 Everything 的思路一致（Everything 用 NTFS USN + 后缀树，
  移动端无 USN，故用倒排代替）。
- 单字符查询退化为线性扫描（万级文件名 <10ms）。
- 同路径重复 Add 会先删旧记录，天然支持增量重扫。

### ContentStore（v0 → M3）

v0 为内存朴素实现（64MB 上限、子串匹配、命中摘要），
接口与 `Search`/`Add` 保持稳定；M3 替换为
**bleve v2（Apache-2.0）+ gse 中文分词（Apache-2.0）** 的持久化索引，
索引文件落盘应用私有目录，启动时增量加载。

## 4. 文档正文抽取

| 格式 | 方案 | 阶段 |
|------|------|------|
| .docx / .pptx / .xlsx | 标准库 zip+xml 流式抽取（`core/docparse.go`），零三方依赖 | ✅ M1 |
| .pdf | ledongthuc/pdf（BSD-3） | M3 |
| .doc / .ppt / .xls / .wps | **Kotlin 层 POI 兜底**：宿主实现 `ExternalParser.extractText()` 并 `setExternalParser` 注册；POI 依赖用 `poi-scratchpad` 精简引入（约 +3MB，按需） | M2 决策 |

> POI 兜底接口设计为可选注入：不上传任何解析依赖时引擎照常工作，
> 只是旧格式文档不参与全文索引（文件名搜索不受影响）。

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
  因此 `build-aar.sh` 固定携带
  `-ldflags='-extldflags=-Wl,-z,max-page-size=16384'`。

## 6. CI

`.github/workflows/ci.yml`：

1. `go vet` + `go test ./...`（引擎单元测试，无需安卓环境）
2. `./build-aar.sh`（gomobile bind，NDK 用 runner 预装 `$ANDROID_NDK_LATEST_HOME`）
3. `gradle wrapper --gradle-version 8.9 && ./gradlew :app:assembleDebug`
4. 产物上传：debug APK + engine.aar

## 7. 里程碑

| 里程碑 | 内容 | 状态 |
|--------|------|------|
| M1 | 骨架：引擎 API/遍历/文件名索引/OOXML 抽取/外壳 UI/CI | ✅ |
| M2 | 真机联调、增量重扫节流、POI 兜底决策、旧格式接入 | ⬜ |
| M3 | bleve+gse 持久化全文索引、PDF 抽取、索引落盘与启动恢复 | ⬜ |
| M4 | 搜索语法（`type:` `dir:` 过滤）、体验打磨、发布渠道 | ⬜ |

## 8. 许可

- 本项目：Apache-2.0。
  选择理由：依赖树全部为宽松协议（BSD/MIT/Apache），GPL 传染保护暂无必要；
  Apache-2.0 起步保留未来切换 GPL-3.0 的自由（单向门：Apache→GPL 可行，
  反向需全体贡献者同意）。若未来引入 GPL 代码，整个项目须随之切换 GPL-3.0，
  这也是依赖排查中放弃 AGPL 的 `fumiama/go-docx`（改用 MIT 的 godocx）的原因。
- 原版 Anything（SumStudio）：闭源，本项目未使用其任何代码，仅致敬其产品思路。
