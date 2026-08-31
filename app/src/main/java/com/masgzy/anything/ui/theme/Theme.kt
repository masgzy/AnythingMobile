package com.masgzy.anything.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.masgzy.anything.data.AppSettings
import com.masgzy.anything.data.RoleColors
import com.masgzy.anything.data.ThemeMode

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
 *  - 所有颜色变更带 tween(400) 过渡动画（ImageToolbox 同款）。
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
    val colorScheme = remember(settings, dark) {
        context.getColorScheme(
            isDarkTheme = dark,
            amoledMode = settings.amoled,
            colorTuple = settings.roleColors.toColorTuple(),
            style = settings.paletteStyle,
            contrastLevel = settings.themeContrast.toDouble(),
            dynamicColor = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            isInvertColors = settings.invertColors,
        ).animateAllColors()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
