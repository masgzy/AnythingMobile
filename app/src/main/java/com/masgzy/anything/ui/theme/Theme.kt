package com.masgzy.anything.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.masgzy.anything.data.AppSettings
import com.masgzy.anything.data.DesignStyle
import com.masgzy.anything.data.RoleColors
import com.masgzy.anything.data.ThemeMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** RoleColors（六角色存储模型）-> ColorTuple（调色引擎种子模型）。 */
fun RoleColors.toColorTuple() = ColorTuple(
    primary = Color(primary),
    secondary = Color(secondary),
    tertiary = Color(tertiary),
    surface = Color(surface),
    neutralVariant = Color(surfaceVariant),
    error = Color(error),
)

/**
 * 应用主题 —— 1:1 采用 ImageToolbox（T8RIN/ImageToolbox）的调色引擎：
 *
 *  - 主题模式：跟随系统 / 浅色 / 深色（AppSettings.themeMode）；
 *  - 动态取色（Monet）：Android 12+ 开启时直接用平台方案（跟随壁纸）；
 *  - 静态配色：ColorTuple 六角色种子 -> material-kolor HCT DynamicScheme
 *    （调色板风格 PaletteStyle 9 种 + 对比度 contrastLevel -1..1），
 *    再经动态错误色/表现力文字色/浅色表面重推/AMOLED/反转颜色后处理；
 *  - 所有颜色变更带 tween(400) 过渡动画（ImageToolbox 同款）；
 *  - 设计风格（alpha28，KSU UiMode 同款双设计）：
 *      MATERIAL（默认）= 上面的 M3 方案；
 *      MIUIX = 内层 MiuixTheme（miuix ThemeController：ColorSchemeMode
 *      按主题模式×动态取色映射，keyColor 取用户主色种子，调色板风格
 *      与 M3 共享）+ 外层保留 MaterialTheme 兜底（未迁移的 M3 组件
 *      继续用 material-kolor 方案着色，用户自定义配色不丢失），
 *      chrome 层经 [LocalUiMode] + [uiPalette] 派发取 Miuix 色板。
 */
@Composable
fun AnythingTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    // 方案计算不参与重组密集路径（remember 缓存），
    // animateAllColors 是 @Composable 扩展，必须在 remember 之外调用
    val base = remember(settings, dark) {
        context.getColorScheme(
            isDarkTheme = dark,
            amoledMode = settings.amoled,
            colorTuple = settings.roleColors.toColorTuple(),
            style = settings.paletteStyle,
            contrastLevel = settings.themeContrast.toDouble(),
            dynamicColor = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            isInvertColors = settings.invertColors,
        )
    }
    val colorScheme = base.animateAllColors()
    if (settings.designStyle == DesignStyle.MIUIX) {
        // ColorSchemeMode：主题模式 × 动态取色 -> miuix 六态
        // （Monet* 由 miuix 内部经 platformDynamicColors 跟随壁纸）
        val miuixMode = when (settings.themeMode) {
            ThemeMode.SYSTEM ->
                if (settings.dynamicColor) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
            ThemeMode.LIGHT ->
                if (settings.dynamicColor) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
            ThemeMode.DARK ->
                if (settings.dynamicColor) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
        }
        // keyColor：静态配色时取用户主色作种子（miuix colorsFromSeed 生成
        // 整套 HyperOS 色板）；动态取色时置 null（跟随壁纸）
        val keyColor: Color? =
            if (settings.dynamicColor) null else Color(settings.roleColors.primary)
        val miuixStyle = remember(settings.paletteStyle) {
            runCatching {
                ThemePaletteStyle.valueOf(settings.paletteStyle.name)
            }.getOrDefault(ThemePaletteStyle.TonalSpot)
        }
        val controller = remember(miuixMode, keyColor, miuixStyle, dark) {
            ThemeController(
                miuixMode,
                keyColor = keyColor,
                isDark = dark,
                paletteStyle = miuixStyle,
                // Spec2021 与 material-kolor 默认规格一致，两风格色相不漂移
                colorSpec = ThemeColorSpec.Spec2021,
            )
        }
        MiuixTheme(controller = controller) {
            CompositionLocalProvider(LocalUiMode provides DesignStyle.MIUIX) {
                MaterialTheme(colorScheme = colorScheme, content = content)
            }
        }
    } else {
        CompositionLocalProvider(LocalUiMode provides DesignStyle.MATERIAL) {
            MaterialTheme(colorScheme = colorScheme, content = content)
        }
    }
}
