package com.masgzy.anything.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.masgzy.anything.data.DesignStyle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 当前生效的设计风格（参照 KernelSU 的 LocalUiMode 同款机制）：
 * AnythingTheme 按 AppSettings.designStyle provide，组件层读取后
 * 在 Material Design 3 与 Miuix（HyperOS 风格）之间派发。
 */
val LocalUiMode = staticCompositionLocalOf { DesignStyle.MATERIAL }

/**
 * 双风格共用的色角色集合（M3 色角色命名）。
 *
 * 设计风格切换的本质是「色源切换」：MD3 模式从 MaterialTheme.colorScheme
 * （material-kolor 调色引擎）取色；Miuix 模式从 MiuixTheme.colorScheme
 * （miuix ThemeController 生成的 HyperOS 色板）取色。两者主色种子
 * （用户自定义主色 / 动态取色）与调色板风格共享同一套设置，
 * 因此 chrome（顶栏/分组卡/底栏/开关）在两种风格下都保持一致的
 * 强调色语义。M3 几何的容器（Surface/AlertDialog 等）继续可用，
 * 未迁移组件的观感由 MaterialTheme 兜底。
 */
@Immutable
data class UiPalette(
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    /** 次级文字/未选中态图标（M3 onSurfaceVariant 语义）。 */
    val onSurfaceVariant: Color,
    /** 分组项副标题的更弱一级文字（Miuix onSurfaceVariantSummary 语义）。 */
    val onSurfaceVariantSummary: Color,
    val outline: Color,
    /** 细分割线（Miuix dividerLine 语义）。 */
    val outlineVariant: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
)

/** 按当前设计风格解析色板（MD3 = material-kolor 方案；Miuix = HyperOS 色板）。 */
@Composable
fun uiPalette(): UiPalette = if (LocalUiMode.current == DesignStyle.MIUIX) {
    MiuixTheme.colorScheme.let { m ->
        UiPalette(
            background = m.background,
            onBackground = m.onBackground,
            surface = m.surface,
            onSurface = m.onSurface,
            surfaceContainer = m.surfaceContainer,
            surfaceContainerHigh = m.surfaceContainerHigh,
            surfaceContainerHighest = m.surfaceContainerHighest,
            primary = m.primary,
            onPrimary = m.onPrimary,
            primaryContainer = m.primaryContainer,
            onPrimaryContainer = m.onPrimaryContainer,
            secondary = m.secondary,
            onSecondary = m.onSecondary,
            secondaryContainer = m.secondaryContainer,
            onSecondaryContainer = m.onSecondaryContainer,
            onSurfaceVariant = m.onSurfaceSecondary,
            onSurfaceVariantSummary = m.onSurfaceVariantSummary,
            outline = m.outline,
            outlineVariant = m.dividerLine,
            error = m.error,
            onError = m.onError,
            errorContainer = m.errorContainer,
            onErrorContainer = m.onErrorContainer,
        )
    }
} else {
    MaterialTheme.colorScheme.let { c ->
        UiPalette(
            background = c.background,
            onBackground = c.onBackground,
            surface = c.surface,
            onSurface = c.onSurface,
            surfaceContainer = c.surfaceContainer,
            surfaceContainerHigh = c.surfaceContainerHigh,
            surfaceContainerHighest = c.surfaceContainerHighest,
            primary = c.primary,
            onPrimary = c.onPrimary,
            primaryContainer = c.primaryContainer,
            onPrimaryContainer = c.onPrimaryContainer,
            secondary = c.secondary,
            onSecondary = c.onSecondary,
            secondaryContainer = c.secondaryContainer,
            onSecondaryContainer = c.onSecondaryContainer,
            onSurfaceVariant = c.onSurfaceVariant,
            onSurfaceVariantSummary = c.onSurfaceVariant,
            outline = c.outline,
            outlineVariant = c.outlineVariant,
            error = c.error,
            onError = c.onError,
            errorContainer = c.errorContainer,
            onErrorContainer = c.onErrorContainer,
        )
    }
}
