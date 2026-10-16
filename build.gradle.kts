// AnythingMobile 根构建脚本
// 插件版本统一在此声明，子模块引用时不带版本号。
// 仓库源与插件管理见 settings.gradle.kts。
//
// 2026-08 工具链整体升级（对齐 ImageToolbox 现役版本集）：
//   AGP 9.3.2 启用内置 Kotlin（built-in Kotlin）——
//   官方迁移指南：org.jetbrains.kotlin.android 插件不再需要，
//   且与内置 Kotlin 共存会直接冲突，因此这里只保留 Compose 编译器插件。
plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
