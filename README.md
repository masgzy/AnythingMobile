<div align="center">

# Anything

**安卓本地文件毫秒级搜索 —— 文件名 + Office 文档全文检索**

[![Release](https://img.shields.io/github/v/release/masgzy/AnythingMobile?include_prereleases&style=flat-square&label=%E6%9C%80%E6%96%B0%E7%89%88)](https://github.com/masgzy/AnythingMobile/releases)
[![CI](https://img.shields.io/github/actions/workflow/status/masgzy/AnythingMobile/ci.yml?branch=main&style=flat-square&label=CI)](https://github.com/masgzy/AnythingMobile/actions/workflows/ci.yml)
[![Stars](https://img.shields.io/github/stars/masgzy/AnythingMobile?style=flat-square&logo=github)](https://github.com/masgzy/AnythingMobile/stargazers)
[![License](https://img.shields.io/github/license/masgzy/AnythingMobile?style=flat-square&label=%E8%AE%B8%E5%8F%AF%E8%AF%81)](LICENSE)

![Rust](https://img.shields.io/badge/Rust-1.85%2B-DEA584?style=flat-square&logo=rust&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-7.0%2B%20%7C%20API%2024%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Material You](https://img.shields.io/badge/UI-Material%20You%20%28M3%29-6750A4?style=flat-square)

致敬 Windows 平台的 [Everything](https://www.voidtools.com/)，安卓端开源实现。
**Rust 高性能内核**（tantivy + jieba）× Kotlin Compose 原生外壳 · **完全离线 · 无广告 · 无追踪**

</div>

---

## 它能做什么

| 功能 | 说明 |
|------|------|
| 三页签搜索 | 文件名 / office 文档正文 / 目录名，与原版 Anything 一致 |
| 底栏导航 | 搜索 / 目录 / 设置三页切换；悬浮胶囊底栏（默认开启，NPatch 同款），液态玻璃质感可开（Android 13+，背景实时模糊 + 折射高光） |
| 自选搜索目录 | 系统文件夹选择器（SAF / 文件提供器 API）授权任意目录纳入索引；索引范围三模式可选：**全盘+目录** / **仅目录** / **仅全盘**（alpha26） |
| 文件名秒搜 | 2-gram 倒排索引，边输入边出结果 |
| 文档全文搜索 | docx / xlsx / pptx / pdf 正文内容也能搜；旧版 .doc / .xls / .ppt / .wps 由内置解析器兜底 |
| 持久化全文索引 | tantivy + jieba 中文分词的倒排索引落盘应用私有目录，启动秒级恢复，正文不再占用运行内存 |
| 搜索语法 | `type:视频` 限定类别、`dir:Download` 限定目录、空格分隔多词（详见抽屉"搜索语法"） |
| 自动更新索引 | 每次打开应用自动增量扫描，有变动才重解析，秒级完成；提示"更新索引中… / 索引更新完成" |
| 类别快筛 | 底部圆钮一键筛选：视频 / 音乐 / 图片 / 文档；office 页可筛 Word / Excel / PPT |
| 结果管理 | 点击看详情（位置/大小/时间）、直接打开或发送，长按多选批量删除 |
| 外观设置 | 主题模式（跟随系统/浅色/深色）、动态取色开关、12 色主题色板、AMOLED 纯黑（对齐 ImageToolbox） |
| 快捷磁贴 | 下拉通知栏点击磁贴，一键直达搜索 |
| Android/data 扫描 | 借助 Shizuku / Stellar 特权通道收录应用数据目录（Android 11+ 隔离区）的文件名与目录名；alpha26 起入口在「目录」页，与添加目录同列的一个条目 |
| 调试日志 | 设置内可切换记录等级（Verbose~Error），日志页筛选查看、一键导出分享，用于远程问题定位 |
| 检查更新 | 应用内检查新版本（GitHub Releases API）：手动点击「版本」项或开启启动自动检查，发现更新弹出更新日志并跳转下载页；关于页可直达 GitHub 仓库。另有**离线版**（下表 `_offline` 后缀）：彻底不做检查更新、连 INTERNET 权限都不声明 |
| 纯本地索引 | 索引/搜索/解析零网络请求，数据不出设备；INTERNET 权限仅用于检查更新（GitHub API），无任何遥测 |

## 为什么是 Rust

alpha20 起搜索引擎内核由 Go（bleve + gse，经 gomobile 打包）整体迁移到 **Rust FFI**：

| 维度 | Go/gomobile（≤alpha19） | Rust（alpha20 起） |
|------|------------------------|-------------------|
| 引擎单 ABI 体积 | ~10MB（Go runtime 下限） | **~7.1MB**（APK 内不压缩存储、mmap 直载，alpha25 实测 7.06MB；不压缩以保证零解压加载延迟与 16KB 对齐） |
| JNI 调用 | 经 gobind 桥接层转发 | 直连 FFI，µs 级进入引擎 |
| 全文索引 | bleve scorch + gse | tantivy + jieba（词典同源，分词口径一致） |
| 索引期内存 | 峰值可达数百 MB | 显著下降（tantivy 分段写入 + 攒批提交） |
| 崩溃安全 | panic 跨界即进程退出 | FFI 边界全量 `catch_unwind`，内部故障转为一条错误回调 |

搜索语义与 alpha19 完全一致：文件名/目录名 2-gram 子串检索、全文"bigram 候选收敛 + 精确子串校验"、
`type:` / `dir:` 过滤语法、两轮配额的结果合并策略。旧版 Go 索引格式不兼容新格式，
升级后首次启动自动全量重建（快照与全文库均如此），无需任何手动迁移。

## 下载安装

**系统要求**：Android 7.0（API 24）及以上；已适配 Android 15 的 16KB 内存页设备。

### 正式版（推荐）

在 [Releases](https://github.com/masgzy/AnythingMobile/releases) 页面下载对应机型的 APK 安装
（当前处于 alpha 阶段，功能快速迭代中）：

| 文件 | 适合谁 |
|------|--------|
| `AnythingMobile-…-arm64-v8a.apk` | 2016 年后的绝大多数手机（推荐） |
| `AnythingMobile-…-armeabi-v7a.apk` | 更早期的 32 位手机 |
| `AnythingMobile-…-x86_64.apk` | 模拟器 / ChromeOS 设备 |
| `AnythingMobile-…-universal.apk` | 不确定就选它，通吃所有机型（体积稍大） |
| `AnythingMobile-…-<abi>_offline.apk` | **离线版**（alpha26）：不检查更新、不声明 INTERNET 权限，适合不愿让应用具备联网能力的用户；与标准版同 applicationId，可直接覆盖互装 |

> **升级提示**：v1.0.0-alpha12 起改用正式证书签名，此后可覆盖升级；
> 从 alpha11 及更早版本升级需先卸载旧版。

### 体验版（CI 构建）

每一次代码提交都会自动构建最新版本：

1. 前往 [Actions · Android CI](https://github.com/masgzy/AnythingMobile/actions/workflows/ci.yml)；
2. 点开最新一次成功的运行，在页面底部 **Artifacts** 区域按需下载：

   | 文件 | 适合谁 |
   |------|--------|
   | `AnythingMobile-…-arm64-v8a.apk` | 2016 年后的绝大多数手机（推荐） |
   | `AnythingMobile-…-armeabi-v7a.apk` | 更早期的 32 位手机 |
   | `AnythingMobile-…-x86_64.apk` | 模拟器 / ChromeOS 设备 |
   | `AnythingMobile-…-universal.apk` | 不确定就选它，通吃所有机型（体积稍大） |

3. 允许"安装未知来源应用"后安装。下载得到的就是 APK 本体，无需解压。

## 三步上手

1. **授权**：首次启动点击"去授权"，授予「所有文件访问」权限（全盘索引必需）；
2. **等待首次索引**：首次启动自动建立索引（耗时取决于文件数量，通常几十秒），之后每次打开都会自动增量更新，无变动时秒级完成；
3. **搜索**：输入关键词，文件名、目录名与文档全文实时命中，点击看详情，长按可多选删除。

**可选：扫描 Android/data（应用数据目录）**

Android 11+ 把 `/sdcard/Android/data` 对普通应用隔离，需借助特权框架：

1. 安装并启动 [Shizuku](https://shizuku.rikka.app/download/) 或 [Stellar](https://github.com/roro2239/Stellar)（无线调试或 Root 启动均可）；
2. 进入应用「目录」页，打开列表里的「Android/data（应用数据目录）」条目（alpha26 起自设置页迁入，与添加目录同列）；首次开启会引导 Shizuku / Stellar 授权；
3. 授权成功后自动枚举并收录（仅文件名/目录名，不含全文；点击 Android/data 命中项会先导出副本再打开）。

## 常见问题

**为什么要"所有文件访问"权限？**
全盘搜索必须读取每个文件的元数据与内容。所有数据 100% 保留在你的设备上：
索引/搜索/解析零网络请求，唯一用到网络的是**检查更新**（GitHub Releases API，
仅在你点击或开启自动检查时发生，可在 AndroidManifest.xml 与系统权限列表中验证）。
如果连这一点也不想要：Releases 页的 `_offline` 离线版连 INTERNET 权限都不声明，
应用在系统层面就不具备联网能力。系统云备份 / 设备迁移同样不会带走任何数据：
应用已声明 `allowBackup=false`，索引快照与全文索引只存在于本机私有目录。

**索引有条目数上限吗？**
没有。"已收录 N 项"是引擎实际计数（内存态名称索引 + 全文库之和的文件数），
不存在硬编码上限；收录量只取决于磁盘内容与设备内存（十万级条目约占 10~20MB）。

**能搜索旧版 Office（.doc / .xls / .ppt）的内容吗？**
能。应用内置了旧版二进制格式的解析器（Kotlin 层实现，无需任何额外权限与组件），
.doc / .xls / .ppt / .wps 与新版 Office、PDF 一样参与全文搜索。
个别加密或损坏文档解析失败时，其文件名仍可正常搜索。

**哪些文件能搜"正文"？**
参与全文索引的格式：`.docx / .xlsx / .pptx / .pdf`（内置解析）+
`.doc / .xls / .ppt / .wps`（Kotlin 层兜底解析）。**纯文本类
（.txt / .md / 各类代码文件）、epub 等暂不参与全文索引** —— 这些文件
只能按文件名搜索。这是与原版 Anything 一致的定位（Office 文档全文），
如需覆盖更多格式可在 issue 提需求。

**会常驻后台耗电吗？**
不会。只有你打开应用时才增量更新索引（仅扫描变动部分），平时没有任何后台活动。也可在设置中关闭自动更新。

**搜索语法怎么用？**
在搜索框中混用：`type:视频 家人` 只搜视频类文件、`dir:Download 合同`
只搜 Download 目录下的文件、`年度 报告`（空格分隔）要求同时包含两个词。
完整速查见应用抽屉菜单的"搜索语法"。

**搜索结果是最新的吗？**
进入应用时会自动增量扫描：新增、修改、删除的文件都会同步到索引，完成后会提示"索引更新完成"。
需要说明：应用不常驻后台、不监听文件变动，因此**应用运行期间**外部新增或删除的文件
不会实时出现——可在设置页点击"立即更新索引"手动同步，或下次进入应用时自动同步。

**从 alpha19 及更早版本升级，索引会丢失吗？**
索引数据结构换成了 Rust 格式，首次启动会自动全量重建一次（耗时同首次安装建索引），
之后照常增量更新，无需手动操作。

## 路线图

- [x] M1 骨架：引擎 + Compose 外壳 + CI 自动构建
- [x] M2 增量重扫：进入应用自动更新索引 + "索引更新完成"提示；目录名搜索；多选/详情/删除；外观设置（对齐 ImageToolbox）
- [x] M2.5 旧版 Office：.doc / .xls / .ppt / .wps 正文解析下沉 Kotlin 层（alpha13）
- [x] M3 持久化全文索引 + PDF 文本抽取（alpha14）：正文落盘不占运行内存，升级旧快照自动重建全文
- [x] M4 搜索语法 `type:` / `dir:` 过滤 + 全文命中相关性排序（alpha14）
- [x] M5 Rust FFI 迁移：引擎整体换用 Rust（tantivy + jieba），APK 体积与索引内存大幅下降（alpha20）
- [x] 应用内检查更新 + 关于页跳仓库（alpha24）；中文 PDF UniGB-UCS2 盲区修复（vendor 补丁，alpha24）；Material Symbols 图标迁移（alpha25）
- [x] 索引范围三模式（全盘+目录 / 仅目录 / 仅全盘）+ Android/data 目录条目化 + 离线版发行渠道（alpha26）
- [x] 安全审计加固：SAF 解析超时 + zip 解压字节上限、外部路径边界校验、Kotlin CFB 解析器防御、FileProvider 暴露收窄、更新跳转白名单（alpha27）
- [ ] 后续：体验打磨、F-Droid 分发、应用内删除 Android/data 文件（Shizuku）

## 致谢

- [voidtools Everything](https://www.voidtools.com/) —— 灵感来源；
- **Anything**（SumStudio / SincereXIA 开发的闭源应用）—— 本仓库为致敬式开源重写，未使用其任何代码；
- [tantivy](https://github.com/quickwit-oss/tantivy)（MIT）与 [jieba-rs](https://github.com/messense/jieba-rs)（MIT）—— Rust 检索生态的中坚力量；
- [ImageToolbox](https://github.com/T8RIN/ImageToolbox) —— Material You 界面设计参考；
- [NPatch](https://github.com/7723mod/NPatch) —— 文件夹选择器（SAF 目录授权）设计与实现参考（GPL-3.0）；
- [vivo Sans](https://developers.vivo.com/) —— 启动图标 "Anything" 字标使用 vivo 官方免费商用字体 vivo Sans（Global Heavy）字形轮廓；本仓库不分发字体文件本身，仅嵌入由其创作的图标作品。

<details>
<summary><b>开发者入口（架构 / 构建 / 依赖许可）</b></summary>

- 架构设计与里程碑：[docs/architecture.md](docs/architecture.md)；Rust 引擎契约：[engine/README.md](engine/README.md)
- 本地构建（Rust stable + Android NDK + JDK 17）：

  ```bash
  ./engine/build-so.sh          # Rust 引擎 -> app/src/main/jniLibs/<abi>/libanything.so
                                # （cargo-ndk 三 ABI 交叉编译 + Android 15 16KB 页对齐）
  ./gradlew :app:assembleDebug  # Gradle Wrapper 已入库（9.7.1），产出多 ABI + universal APK
  ```

- 引擎单测：`cd engine && cargo test`（121 个用例：索引/搜索/解析/快照/并发契约）
- 核心依赖均为宽松许可证，与本项目 GPL-3.0 完全兼容：
  tantivy (MIT) · jieba-rs (MIT) · zip (MIT) · pdf-extract (MIT/Apache-2.0) · flate2 (MIT/Apache-2.0) · jni (MIT/Apache-2.0) · serde (MIT/Apache-2.0)
- 欢迎通过 Issue 与 PR 参与贡献；提交后 CI 自动完成引擎测试与全架构构建。

</details>

## 许可证

本项目基于 [GPL-3.0](LICENSE) 协议开源：任何人在协议范围内自由使用、修改、再分发，衍生作品须以同协议开源。

- 文件夹选择器（SAF 目录授权）的设计与实现参考了 [NPatch](https://github.com/7723mod/NPatch)（GPL-3.0），感谢其作者；
- 依赖组件（tantivy / jieba-rs / zip / pdf-extract 等）均为 MIT / Apache 宽松许可证，与 GPL-3.0 兼容；
