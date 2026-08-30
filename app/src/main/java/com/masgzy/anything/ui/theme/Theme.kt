package com.masgzy.anything.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 简洁配色：靛蓝主色 + 暖橙强调，后续可换成主题色系统。
private val Indigo = Color(0xFF3F51B5)
private val IndigoDark = Color(0xFF303F9F)
private val Amber = Color(0xFFFFB300)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EAF6),
    secondary = Amber,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FA8DA),
    onPrimary = Color.Black,
    primaryContainer = IndigoDark,
    secondary = Amber,
)

@Composable
fun AnythingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
