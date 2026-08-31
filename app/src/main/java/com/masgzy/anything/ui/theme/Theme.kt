package com.masgzy.anything.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.masgzy.anything.data.AppSettings
import com.masgzy.anything.data.ThemeMode

/**
 * 应用主题 —— 对齐 ImageToolbox 的外观体系：
 *
 *  - 主题模式：跟随系统 / 浅色 / 深色（AppSettings.themeMode）；
 *  - 动态取色（Monet）：Android 12+ 默认开启，跟随壁纸；
 *  - 角色化配色（advancedPalette）：ImageToolbox 配色方案，
 *    主色/辅助色/第三色/表面/中性变体/错误六角色直接指定，
 *    其余角色由各自色相 HSL 派生；
 *  - 预设主题色：以上皆未启用时使用 AppSettings.seedColor；
 *  - AMOLED 纯黑：深色模式下 surface/background 置纯黑。
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
        when {
            settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                baselineDynamic(context, dark, settings.amoled)
            settings.advancedPalette -> roleScheme(settings.roleColors, dark, settings.amoled)
            else ->
                seedScheme(Color(settings.seedColor), dark, settings.amoled)
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** Android 12+ 壁纸动态取色；AMOLED 时压黑表面色。 */
private fun baselineDynamic(context: android.content.Context, dark: Boolean, amoled: Boolean) =
    run {
        val base = if (dark) dynamicDark(context) else dynamicLight(context)
        if (amoled && dark) base.amoledized() else base
    }

@Suppress("NewApi")
private fun dynamicLight(context: android.content.Context) =
    androidx.compose.material3.dynamicLightColorScheme(context)

@Suppress("NewApi")
private fun dynamicDark(context: android.content.Context) =
    androidx.compose.material3.dynamicDarkColorScheme(context)

/**
 * 由种子色派生完整的 M3 明/暗方案。
 *
 * 不引入第三方取色库：以种子色为 primary，用 HSL 近似生成 container/
 * secondary/tertiary 等角色，视觉上保持 M3 的高容器/低容器层次。
 */
private fun seedScheme(seed: Color, dark: Boolean, amoled: Boolean) =
    run {
        val argb = seed.toArgb()
        val (h, s, l) = hslOf(argb)

        fun sl(light: Float, sat: Float = s): Color =
            colorFromHsl(h, sat.coerceIn(0f, 1f), light.coerceIn(0f, 1f))

        val scheme = if (dark) {
            darkColorScheme(
                primary = sl(0.80f),
                onPrimary = sl(0.22f, s.coerceAtMost(0.5f)),
                primaryContainer = sl(0.36f),
                onPrimaryContainer = sl(0.90f),
                secondary = sl(0.78f, s * 0.45f),
                onSecondary = sl(0.20f),
                secondaryContainer = sl(0.32f, s * 0.5f),
                onSecondaryContainer = sl(0.90f, s * 0.5f),
                tertiary = sl(0.80f, s * 0.6f),
                tertiaryContainer = sl(0.34f, s * 0.6f),
                surface = sl(0.09f, s * 0.12f),
                onSurface = sl(0.92f, s * 0.1f),
                surfaceVariant = sl(0.16f, s * 0.2f),
                onSurfaceVariant = sl(0.80f, s * 0.2f),
                surfaceContainer = sl(0.12f, s * 0.15f),
                surfaceContainerHigh = sl(0.15f, s * 0.15f),
                outline = sl(0.60f, s * 0.2f),
                outlineVariant = sl(0.30f, s * 0.15f),
            )
        } else {
            lightColorScheme(
                primary = sl(0.42f),
                onPrimary = Color.White,
                primaryContainer = sl(0.90f),
                onPrimaryContainer = sl(0.22f),
                secondary = sl(0.38f, s * 0.45f),
                onSecondary = Color.White,
                secondaryContainer = sl(0.88f, s * 0.5f),
                onSecondaryContainer = sl(0.18f),
                tertiary = sl(0.40f, s * 0.6f),
                tertiaryContainer = sl(0.88f, s * 0.6f),
                surface = sl(0.98f, s * 0.12f),
                onSurface = sl(0.14f, s * 0.15f),
                surfaceVariant = sl(0.92f, s * 0.2f),
                onSurfaceVariant = sl(0.32f, s * 0.2f),
                surfaceContainer = sl(0.95f, s * 0.15f),
                surfaceContainerHigh = sl(0.92f, s * 0.15f),
                outline = sl(0.50f, s * 0.2f),
                outlineVariant = sl(0.82f, s * 0.15f),
            )
        }
        if (amoled && dark) scheme.amoledized() else scheme
    }

/**
 * 角色化配色方案（ImageToolbox 配色方案功能）：
 *
 * 用户直接指定的六个角色（主色/辅助色/第三色/表面/中性变体/错误）
 * 在浅色模式下原样使用（on* 文字色按亮度自动黑/白保证对比），
 * 深色模式下保持各自色相、按 M3 明暗层次重推亮度；
 * 容器色、轮廓、表面容器等其余角色由对应角色的色相派生。
 */
private fun roleScheme(roles: com.masgzy.anything.data.RoleColors, dark: Boolean, amoled: Boolean) =
    run {
        // 每个角色的 HSL 分量（色相/饱和度保留，亮度按需重推）
        val p = hslOf(roles.primary)
        val s = hslOf(roles.secondary)
        val t = hslOf(roles.tertiary)
        val sf = hslOf(roles.surface)
        val sv = hslOf(roles.surfaceVariant)
        val er = hslOf(roles.error)

        fun of(c: Triple<Float, Float, Float>, light: Float, satScale: Float = 1f): Color =
            colorFromHsl(c.first, (c.second * satScale).coerceIn(0f, 1f), light.coerceIn(0f, 1f))

        // on* 文字色：跟随所属角色色相，亮度按对比度要求固定
        fun onOf(base: Int, darkOn: Boolean): Color {
            val l = hslOf(base).third
            return if (darkOn) {
                if (l > 0.55f) Color.Black else Color.White
            } else {
                if (l > 0.55f) Color(0xFF1B1B1F) else Color.White
            }
        }

        val scheme = if (dark) {
            darkColorScheme(
                primary = of(p, 0.80f),
                onPrimary = of(p, 0.22f, 0.5f),
                primaryContainer = of(p, 0.36f),
                onPrimaryContainer = of(p, 0.90f),
                secondary = of(s, 0.78f),
                onSecondary = of(s, 0.20f),
                secondaryContainer = of(s, 0.32f),
                onSecondaryContainer = of(s, 0.90f),
                tertiary = of(t, 0.80f),
                onTertiary = of(t, 0.20f),
                tertiaryContainer = of(t, 0.34f),
                onTertiaryContainer = of(t, 0.90f),
                error = of(er, 0.80f),
                onError = of(er, 0.20f),
                errorContainer = of(er, 0.30f),
                onErrorContainer = of(er, 0.90f),
                background = of(sf, 0.09f, 0.12f),
                onBackground = of(sf, 0.92f, 0.10f),
                surface = of(sf, 0.09f, 0.12f),
                onSurface = of(sf, 0.92f, 0.10f),
                surfaceVariant = of(sv, 0.16f),
                onSurfaceVariant = of(sv, 0.80f),
                surfaceContainer = of(sf, 0.12f, 0.15f),
                surfaceContainerHigh = of(sf, 0.15f, 0.15f),
                surfaceContainerHighest = of(sf, 0.19f, 0.15f),
                outline = of(sv, 0.60f),
                outlineVariant = of(sv, 0.30f, 0.15f),
            )
        } else {
            lightColorScheme(
                primary = Color(roles.primary),
                onPrimary = onOf(roles.primary, darkOn = false),
                primaryContainer = of(p, 0.90f),
                onPrimaryContainer = of(p, 0.22f),
                secondary = Color(roles.secondary),
                onSecondary = onOf(roles.secondary, darkOn = false),
                secondaryContainer = of(s, 0.88f),
                onSecondaryContainer = of(s, 0.18f),
                tertiary = Color(roles.tertiary),
                onTertiary = onOf(roles.tertiary, darkOn = false),
                tertiaryContainer = of(t, 0.88f),
                onTertiaryContainer = of(t, 0.18f),
                error = Color(roles.error),
                onError = onOf(roles.error, darkOn = false),
                errorContainer = of(er, 0.90f),
                onErrorContainer = of(er, 0.18f),
                background = Color(roles.surface),
                onBackground = of(sf, 0.14f, 0.15f),
                surface = Color(roles.surface),
                onSurface = of(sf, 0.14f, 0.15f),
                surfaceVariant = Color(roles.surfaceVariant),
                onSurfaceVariant = of(sv, 0.32f),
                surfaceContainer = of(sf, 0.95f, 0.15f),
                surfaceContainerHigh = of(sf, 0.92f, 0.15f),
                surfaceContainerHighest = of(sf, 0.89f, 0.15f),
                outline = of(sv, 0.50f),
                outlineVariant = of(sv, 0.82f, 0.15f),
            )
        }
        if (amoled && dark) scheme.amoledized() else scheme
    }

/** AMOLED：深色方案下表面色全部置纯黑，容器色保持低亮度层级。 */
private fun androidx.compose.material3.ColorScheme.amoledized() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceContainerHigh = Color(0xFF121212),
    surfaceContainerHighest = Color(0xFF1A1A1A),
)

private fun hslOf(argb: Int): Triple<Float, Float, Float> {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val l = (max + min) / 2f
    val s = if (delta == 0f) 0f else delta / (1f - kotlin.math.abs(2f * l - 1f))
    val h = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0) it + 360f else it }
    return Triple(h, s, l)
}

private fun colorFromHsl(h: Float, s: Float, l: Float): Color {
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
    )
}
