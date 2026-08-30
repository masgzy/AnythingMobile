<div align="center">

# Anything

**安卓本地文件毫秒级搜索 —— 文件名 + Office 文档全文检索**

[![Release](https://img.shields.io/github/v/release/masgzy/AnythingMobile?include_prereleases&style=flat-square&label=%E6%9C%80%E6%96%B0%E7%89%88)](https://github.com/masgzy/AnythingMobile/releases)
[![CI](https://img.shields.io/github/actions/workflow/status/masgzy/AnythingMobile/ci.yml?branch=main&style=flat-square&label=CI)](https://github.com/masgzy/AnythingMobile/actions/workflows/ci.yml)
[![Stars](https://img.shields.io/github/stars/masgzy/AnythingMobile?style=flat-square&logo=github)](https://github.com/masgzy/AnythingMobile/stargazers)
[![License](https://img.shields.io/github/license/masgzy/AnythingMobile?style=flat-square&label=%E8%AE%B8%E5%8F%AF%E8%AF%81)](LICENSE)

![Go](https://img.shields.io/badge/Go-1.23+-00ADD8?style=flat-square&logo=go&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-7.0%2B%20%7C%20API%2024%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Material You](https://img.shields.io/badge/UI-Material%20You%20%28M3%29-6750A4?style=flat-square)

致敬 Windows 平台的 [Everything](https://www.voidtools.com/)，安卓端开源实现。
Go 高性能内核 × Kotlin Compose 原生外壳 · **完全离线 · 无广告 · 无追踪**

</div>

---

## 它能做什么

| 功能 | 说明 |
|------|------|
| 文件名秒搜 | 2-gram 倒排索引，边输入边出结果 |
| 文档全文搜索 | docx / xlsx / pptx 正文内容也能搜（PDF 支持在计划中） |
| Material You | Android 12+ 自动跟随壁纸取色，深浅色跟随系统切换 |
| 快捷磁贴 | 下拉通知栏点击磁贴，一键直达搜索 |
| 直接打开 | 点击结果用任意应用打开或分享 |
| 纯本地运行 | 无网络权限，索引数据不出设备 |

## 下载安装

**系统要求**：Android 7.0（API 24）及以上；已适配 Android 15 的 16KB 内存页设备。

### 正式版（推荐）

首个版本 **v1.0.0-alpha1** 正在筹备中，发布后可直接在
[Releases](https://github.com/masgzy/AnythingMobile/releases) 页面下载 APK 安装。

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
2. **扫描**：点「开始扫描」，等待索引完成（耗时取决于文件数量，通常几十秒）；
3. **搜索**：输入关键词，文件名与文档全文实时命中，点击结果直接打开。

## 常见问题

**为什么要"所有文件访问"权限？**
全盘搜索必须读取每个文件的元数据与内容。本应用没有声明 INTERNET 权限
（可在 AndroidManifest.xml 中验证，安装后也可在系统应用信息里查看权限列表），
所有数据 100% 保留在你的设备上。

**为什么搜不到 .doc / .ppt / .xls 的内容？**
旧版 Office 二进制格式的正文解析正在计划中。这些文件的**文件名**始终可以搜到，
新版 Office（docx / pptx / xlsx）的正文已支持全文搜索。

**会常驻后台耗电吗？**
不会。只有你手动点「开始扫描」时才会工作，平时没有任何后台活动。

**搜索结果是最新的吗？**
索引在你扫描时建立，之后新增或改名的文件需要重新扫描才能被找到。
增量重扫已在路线图中（见下），届时无需每次全盘重建。

## 路线图

- [x] M1 骨架：Go 引擎 + Compose 外壳 + CI 自动构建
- [ ] M2 真机联调、增量重扫、扫描节流
- [ ] M3 bleve + gse 持久化全文索引、PDF 文本抽取
- [ ] M4 搜索语法（类型 / 目录过滤）、外观设置、F-Droid 分发

## 致谢

- [voidtools Everything](https://www.voidtools.com/) —— 灵感来源；
- **Anything**（SumStudio / SincereXIA 开发的闭源应用）—— 本仓库为致敬式开源重写，未使用其任何代码；
- [ImageToolbox](https://github.com/T8RIN/ImageToolbox) —— Material You 界面设计参考。

<details>
<summary><b>开发者入口（架构 / 构建 / 依赖许可）</b></summary>

- 架构设计与里程碑：[docs/architecture.md](docs/architecture.md)
- 本地构建：

  ```bash
  ./build-aar.sh              # Go 引擎 -> AAR（含 Android 15 16KB 页对齐）
  gradle :app:assembleDebug   # 产出多 ABI + universal APK
  ```

- 核心依赖全部为宽松许可证、零 GPL：
  gomobile (BSD-3) · bleve (Apache-2.0) · gse (Apache-2.0) · godocx (MIT) · excelize (BSD-3)
- 欢迎通过 Issue 与 PR 参与贡献；提交后 CI 自动完成引擎测试与全架构构建。

</details>

## 许可证

[Apache License 2.0](LICENSE)
