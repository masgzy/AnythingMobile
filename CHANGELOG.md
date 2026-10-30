# 更新日志

本文件记录面向用户的显著变更。完整的版本说明（含背景与动机）见
[GitHub Releases](https://github.com/masgzy/AnythingMobile/releases)。

本项目处于 alpha 阶段：存储格式与搜索语义可能在小版本间破坏性变更，
升级后可能需要全量重建索引（应用会自动完成，无需手动操作）。

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
