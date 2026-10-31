# 更新日志

本文件记录面向用户的显著变更。完整的版本说明（含背景与动机）见
[GitHub Releases](https://github.com/masgzy/AnythingMobile/releases)。

本项目处于 alpha 阶段：存储格式与搜索语义可能在小版本间破坏性变更，
升级后可能需要全量重建索引（应用会自动完成，无需手动操作）。

## [1.0.0-alpha29] 2026-10-31

### 图标空白回归修复（重要）

- **修复 alpha28 release 包应用内图标全部空白**：miuix-ui 的传递依赖
  把 `androidx.compose.material3` 从 BOM 锁定的 1.4.0 静默仲裁到
  1.5.0-alpha22（alpha 线），该 alpha 版在 R8 下引发图标渲染回归。
  现已强制钉回 1.4.0（经 AAR 字节码核对 miuix-ui 零引用 material3，
  钉住零风险），此后 material3 版本完全由 BOM 决定。
- CI 模拟器冒烟升级为安装 **release 包**（R8 + 正式签名）——
  此类 release-only 回归 debug 包永远看不出来，并自动跳过欢迎页
  截图主界面。

### 设计风格差异化（反馈修复）

- **Miuix 风格色板改用 HyperOS 2 取色规格（Spec2025）**：alpha28
  曾为"两风格色相不漂移"对齐到 Spec2021，实际观感与 MD3 几乎无差别；
  现按反馈改回 HyperOS 原生规格，切换风格有可感知的色感差异。
- **MD3 风格还原为标准 Material Design 3 观感**（此前为模仿 NPatch
  已被魔改成类 Miuix）：
  - 设置分组卡圆角 20dp（HyperOS 连块）→ **12dp（M3 卡片标准）**；
  - 设置项分割线从 0.5dp 缩进细线 → **1dp 标准分割线（对齐图标）**；
  - Miuix 风格下设置项图标改为**主色圆角底盒 + 反色图标**
    （HyperOS 设置观感），MD3 保持裸主色图标（M3 惯例）。

## [1.0.0-alpha28] 2026-10-31

### 双设计风格（参照 KernelSU）

- **设计风格二选一**：设置 → 外观 → 「设计风格」，可在
  **Material Design 3**（默认，历史行为不变）与
  **Miuix · HyperOS 风格** 之间切换，全应用即时生效。
  Miuix 风格经 miuix-ui 的 `ThemeController` 生成 HyperOS 色板
  （顶栏 / 分组卡 / 开关 / 弹层 / 底栏等 chrome 换 HyperOS 观感，
  KSU 同款双设计机制）；与 MD3 共享同一套主题模式、动态取色与
  配色方案设置（主色作 Miuix 色板种子，调色板风格一一映射），
  切换风格不丢失任何配色自定义。
- **液态玻璃底栏两风格通用**：底栏（悬浮胶囊 / 通栏）配色随设计
  风格取当前色板，液态玻璃质感（Android 13+ 背景实时模糊 +
  折射高光）在 Material Design 3 与 Miuix 风格下均可开启。
- 设置页单选弹窗在 Miuix 风格下换用 HyperOS 观感弹层；
  设置分组标题直接使用 miuix `SmallTitle`；开关使用 miuix
  `Switch`（自带 HyperOS 触感反馈）。
- 新增依赖：`top.yukonga.miuix.kmp:miuix-ui:0.9.4`（与既有
  `miuix-blur` 同版本同发布线）。

## [1.0.0-alpha27] 2026-10-30

### 安全加固（外部审计跟进）

- **SAF 直连通道解析超时**（审计 V-1）：目录页「同步索引」的
  `extractDoc` 此前无超时兜底，恶意压缩炸弹文档可让前台满核卡顿
  分钟级；现与扫描流水线同语义（辅助线程 + 45s 超时）。
- **zip 解压字节上限 128MB**（审计 V-1）：单条目解压读取封顶，
  "2.2MB → ~1GB 无文本 XML" 型炸弹的最坏 CPU 从分钟级压回亚秒级。
- **外部路径边界校验**（审计 V-2）：`valid_external_path` 拒绝
  `/root_evil/...` 前缀兄弟目录。
- **Kotlin CFB 解析器加固**（审计 V-4）：旧版 .doc 解析拒绝一切负数
  扇区号（此前可触发数组负索引异常）；DIFAT 链增加环检测与
  "FAT 扇区数 ≤ 文件物理扇区数"上限（此前自环可无界增长致 OOM）。
- **FileProvider 暴露收窄**（审计 V-3）：cache 分享根从全 cache 目录
  收窄到 `shizuku_export/` 子目录。
- **更新跳转白名单**（审计 V-6）：检查更新返回的 `html_url` 只放行
  本仓库 URL，防 API 篡改诱导跳转。
- **崩溃分享知情度**（审计 V-5）：两处分享文案如实列明报告包含
  logcat 尾部运行日志。
- **dev 构建恢复溢出检查**（审计 §4.3）：`.cargo/config.toml` 的
  `overflow-checks=off` 仅保留在 release profile，开发期整数溢出
  立即 panic 暴露。

### 性能

- **宽泛查询物化预算**（审计 §6.1）：名称/目录源检索加
  max(limit×4, 5000) 上限，数万命中的查询不再全量物化数十 MB
  瞬时内存（低端机 LMK 风险消除）；命中超限时 UI 总数为下界近似。

### 工程化

- **修复模拟器仪器化测试任务名**（审计 E-2）：flavor 化后任务应为
  `connectedStandardDebugAndroidTest`，此前 CI 静默失败。
- CI 接入 Gradle Wrapper 校验（审计 V-7，防 wrapper jar 供应链投毒）。
- 新增 `CHANGELOG.md` 与 `CONTRIBUTING.md`（审计 §7）。
- README 新增"哪些文件能搜正文"FAQ（审计 §6.5：纯文本/代码文件
  暂不参与全文索引，如实告知）。

## [1.0.0-alpha26] 2026-10-25

### 新增

- **索引范围三模式**：全盘+目录 / 仅目录 / 仅全盘（替代旧布尔开关，
  旧设置自动迁移）。
- **离线版发行渠道**：每架构新增 `_offline.apk` —— 编译期剪除检查
  更新代码、Manifest 层裁除 INTERNET 权限（系统级无网络），与标准版
  同 applicationId 可互相覆盖安装。

### 变更

- 「扫描 Android/data」从设置页迁入目录列表条目（开关 + 已收录计数
  + 未授权引导），设置页区块改为「特权通道」。

### 性能

- 增量扫描提速：变更文档旧全文删除由逐条 commit 改为攒批单次 commit
  （N 次 commit+reload → 1 次）；快照导出移入落盘线程。

### 文档

- README 诚实化修正（INTERNET 声明陈述）、新增「索引无条目上限」FAQ、
  `.so` 体积改为实测数据；architecture.md / engine/README.md 同步。

## [1.0.0-alpha25] 2026-10-24

### 修复

- **中文 PDF 全文检索修复**：内嵌补丁版 pdf-extract 支持 UniGB-UCS2-H
  等全部 UCS-2 家族 CMap 编码（WPS/Word 中文导出 PDF 此前只能搜文件名）。

### 变更

- 全部界面图标迁移到 Material Symbols；移除 20-34MB 的
  material-icons-extended 依赖，构建提速、包体缩小。

## [1.0.0-alpha24] 2026-10-23

### 新增

- 应用内检查更新（GitHub Releases API，HTTPS + 8s 超时，仅用户触发）；
  关于页一键跳转仓库。
