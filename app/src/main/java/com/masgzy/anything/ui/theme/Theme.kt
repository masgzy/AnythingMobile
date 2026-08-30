package com.masgzy.anything.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 静态回退配色（Android < 12 无 Material You 动态取色时使用）：
// 遵循 M3 色彩角色（primary / onPrimary / container...），靛蓝主色 + 暖橙强调。
private val LightColors = lightColorScheme(
    primary = Color(0xFF4355B9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE0FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5B5D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E1F9),
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF77536D),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBBC3FF),
    onPrimary = Color(0xFF112478),
    primaryContainer = Color(0xFF2B40A0),
    onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC4C5DD),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434559),
    onSecondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFFE6BAD7),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE4E1E9),
)

/**
 * 应用主题。
 *
 * Material You（M3）策略：
 *  - Android 12+（API 31+）：默认使用 dynamicLight/DarkColorScheme 从用户壁纸取色；
 *  - 更早版本：回退到上述静态 M3 配色；
 *  - 明暗跟随系统（isSystemInDarkTheme），深浅色均定义完整色彩角色。
 * 用户可在系统壁纸与样式里改色，应用即时跟随 —— 这就是 Material You。
 */
@Composable
fun AnythingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 保留开关：后续做"外观设置"页时接入用户偏好（参考 ImageToolbox 的主题设置）。
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
