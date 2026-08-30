# AnythingMobile

> 安卓本地文件**毫秒级搜索**工具 —— 文件名 + Office 文档全文检索。
> Go 高性能内核 × Kotlin 原生外壳，开源（Apache-2.0）。

灵感来自 Windows 平台的 [Everything](https://www.voidtools.com/) 与安卓端闭源应用
**Anything**（SumStudio/SincereXIA 开发，本仓库为致敬式开源重写，未使用其任何代码）。

## 架构

```
┌────────────────────────────────────────────┐
│  Kotlin 外壳 (app/)                         │
│  ├─ Compose 搜索界面 / 结果卡片              │
│  ├─ QuickSettings 磁贴入口                  │
│  ├─ MANAGE_EXTERNAL_STORAGE 权限引导         │
│  ├─ FileProvider 文件打开/分享               │
│  └─ ExternalParser 接口（POI 兜底 .doc 等）  │
└──────────────┬─────────────────────────────┘
               │ gomobile bind 生成 AAR（JNI）
┌──────────────▼─────────────────────────────┐
│  Go 核心引擎 (core/)                        │
│  ├─ 并发文件遍历（子树并行 WalkDir）          │
│  ├─ 文件名 2-gram 倒排索引                   │
│  ├─ 朴素全文库 v0 → bleve+gse（M3）          │
│  └─ OOXML 文本抽取（标准库实现，零三方依赖）    │
└────────────────────────────────────────────┘
```

## 当前状态：M1 骨架已就位

- [x] Go 引擎：并发遍历、文件名索引、docx/pptx/xlsx 正文抽取、搜索 API、单元测试
- [x] gobind 绑定层 API（JSON 传参，规避类型限制，见 `docs/architecture.md`）
- [x] Kotlin 外壳：Compose 界面、权限引导、磁贴、文件打开
- [x] CI：`go vet/test` → `gomobile bind`（16KB 页对齐）→ APK 产物
- [ ] M2：真机联调、增量重扫、扫描节流
- [ ] M3：bleve + gse 持久化全文索引、PDF 文本抽取
- [ ] M4：搜索语法（类型/目录过滤）、深色主题打磨、F-Droid 发布

## 快速开始

### 环境要求

Go ≥ 1.23、JDK 17、Android SDK 35、Android NDK（`ANDROID_NDK_HOME` 已配置）。

### 构建

```bash
# 1) 编译 Go 引擎为 AAR（自动安装 gomobile，含 16KB 页对齐）
./build-aar.sh

# 2) 构建 APK
gradle :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

> 没有本地环境？推送任意 commit 后，GitHub Actions 会自动产出
> `AnythingMobile-debug-apk` 与 `engine-aar` 两个 Artifact，直接下载即可。

### 核心库依赖与许可证

| 依赖 | 用途 | 许可证 |
|------|------|--------|
| golang.org/x/mobile (gomobile) | Go→Android 绑定 | BSD-3-Clause |
| blevesearch/bleve v2 *(M3)* | 全文检索引擎 | Apache-2.0 |
| go-ego/gse *(M3)* | 中文分词 | Apache-2.0 |
| qax-os/excelize *(M3，复杂读取)* | xlsx | BSD-3-Clause |
| gomutex/godocx *(M3，结构化读取)* | docx | MIT |
| ledongthuc/pdf *(M3)* | PDF 文本 | BSD-3-Clause |
| AndroidX / Compose | UI | Apache-2.0 |

**GPL 排查结论**：候选依赖中 `fumiama/go-docx` 为 **AGPL-3.0，已弃用**，
由 MIT 的 `gomutex/godocx` 替代；当前依赖树无 GPL/AGPL/LGPL 成员。
本项目采用 **Apache-2.0**（含义见 LICENSE 与 `docs/architecture.md#许可`）。

## 文档

- [架构设计与里程碑](docs/architecture.md)
- [gobind 绑定命名与类型限制速查](docs/architecture.md#gobind-约束)

## 许可

Apache License 2.0
