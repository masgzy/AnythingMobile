/*
 * AnythingMobile 调色引擎 —— 完整移植自 ImageToolbox（T8RIN/ImageToolbox，
 * Apache-2.0）lib/dynamic-theme 模块 DynamicTheme.kt 的方案生成链路：
 *
 *   ColorTuple(六角色种子) -> HCT TonalPalette -> DynamicScheme(variant+contrast)
 *   -> withDynamicErrorColors -> withExpressiveOnColors -> withContrastingSurfaces
 *   -> toAmoled -> invertColors -> outlineVariant/background 修正
 *
 * 底层使用与 ImageToolbox 相同的 material-kolor（Material Color Utilities 的
 * Compose 多平台移植）；因本项目 Kotlin 2.0.20 + Compose BOM 2024.09，
 * 选用与其同源的 2.1.1 版本，API 语义一致（toColorScheme 的 isAmoled 为
 * 必填参数，此处恒传 false —— AMOLED 由下方 toAmoled 移植实现统一处理）。
 */

package com.masgzy.anything.ui.theme

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.SchemeFruitSalad
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeRainbow
import com.materialkolor.scheme.SchemeVibrant
import com.materialkolor.scheme.Variant
import com.materialkolor.toColorScheme

/** 调色板风格（与 ImageToolbox PaletteStyle 一一对应）。 */
enum class PaletteStyle {
    TonalSpot,
    Neutral,
    Vibrant,
    Expressive,
    Rainbow,
    FruitSalad,
    Monochrome,
    Fidelity,
    Content,
}

/**
 * 应用配色种子：主色必填，其余五个角色可选。
 * 与 ImageToolbox ColorTuple 字段一一对应；为 null 的角色由主色按
 * HCT 规则派生（辅助色 chroma/3、第三色 hue+60、表面 chroma/12 等）。
 */
data class ColorTuple(
    val primary: Color,
    val secondary: Color? = null,
    val tertiary: Color? = null,
    val surface: Color? = null,
    val neutralVariant: Color? = null,
    val error: Color? = null,
) {
    override fun toString(): String =
        "ColorTuple(primary=${primary.toArgb()}, secondary=${secondary?.toArgb()}, " +
            "tertiary=${tertiary?.toArgb()}, surface=${surface?.toArgb()}, " +
            "neutralVariant=${neutralVariant?.toArgb()}, error=${error?.toArgb()})"
}

// ---- 派生角色计算（与 ImageToolbox 同名函数公式一致） ----

fun Color.calculateSecondaryColor(): Int {
    val hct = Hct.fromInt(this.toArgb())
    return TonalPalette.fromHueAndChroma(hct.hue, hct.secondaryChroma()).tone(80)
}

fun Color.calculateTertiaryColor(): Int {
    val hct = Hct.fromInt(this.toArgb())
    return TonalPalette.fromHueAndChroma(hct.tertiaryHue(), hct.tertiaryChroma()).tone(80)
}

fun Color.calculateSurfaceColor(): Int {
    val hct = Hct.fromInt(this.toArgb())
    val hue = hct.hue
    val chroma = hct.chroma
    return TonalPalette.fromHueAndChroma(hue, (chroma / 12.0).coerceAtMost(4.0)).tone(90)
}

fun Color.calculateNeutralVariantColor(): Int = calculateSurfaceColor()

fun Color.calculateErrorColor(style: PaletteStyle = PaletteStyle.TonalSpot): Int {
    val hct = Hct.fromInt(this.toArgb())
    return TonalPalette.fromHueAndChroma(
        hue = hct.dynamicErrorHue(),
        chroma = style.dynamicErrorChroma(),
    ).tone(82)
}

private fun Hct.secondaryChroma(): Double = (chroma / 3.0).coerceIn(14.0, 28.0)

private fun Hct.tertiaryChroma(): Double = (chroma / 2.0).coerceIn(20.0, 42.0)

private fun Hct.tertiaryHue(): Double = (hue + 60.0).mod(360.0)

private fun PaletteStyle.dynamicErrorChroma(): Double = when (this) {
    PaletteStyle.Neutral -> 56.0
    PaletteStyle.TonalSpot -> 72.0
    PaletteStyle.Expressive -> 76.0
    PaletteStyle.Vibrant -> 80.0
    else -> 72.0
}

private fun Hct.dynamicErrorHue(): Double {
    val breakpoints = doubleArrayOf(0.0, 3.0, 13.0, 23.0, 33.0, 43.0, 153.0, 273.0, 360.0)
    val hues = doubleArrayOf(12.0, 22.0, 32.0, 12.0, 22.0, 32.0, 22.0, 12.0)
    return hues.getOrElse(
        breakpoints.indexOfLast { hue >= it }.coerceAtMost(hues.lastIndex),
    ) {
        hues.last()
    }
}

private fun Color.blend(color: Color, fraction: Float): Color =
    Color(ColorUtils.blendARGB(this.toArgb(), color.toArgb(), fraction))

// ---- 方案生成 ----

@Composable
fun rememberColorScheme(
    isDarkTheme: Boolean,
    amoledMode: Boolean,
    colorTuple: ColorTuple,
    style: PaletteStyle,
    contrastLevel: Double,
    dynamicColor: Boolean,
    isInvertColors: Boolean,
): ColorScheme {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(
        colorTuple, isDarkTheme, amoledMode, contrastLevel,
        dynamicColor, style, isInvertColors,
    ) {
        derivedStateOf {
            context.getColorScheme(
                isDarkTheme = isDarkTheme,
                amoledMode = amoledMode,
                colorTuple = colorTuple,
                style = style,
                contrastLevel = contrastLevel,
                dynamicColor = dynamicColor,
                isInvertColors = isInvertColors,
            )
        }
    }.value
}

@SuppressLint("NewApi")
fun Context.getColorScheme(
    isDarkTheme: Boolean,
    amoledMode: Boolean,
    colorTuple: ColorTuple,
    style: PaletteStyle,
    contrastLevel: Double,
    dynamicColor: Boolean,
    isInvertColors: Boolean,
): ColorScheme {
    val colorScheme =
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDarkTheme) {
                dynamicDarkColorScheme(this)
            } else {
                dynamicLightColorScheme(this)
            }
        } else {
            val hct = Hct.fromInt(colorTuple.primary.toArgb())
            val hue = hct.hue
            val chroma = hct.chroma

            val a1 = colorTuple.primary.let { TonalPalette.fromInt(it.toArgb()) }

            val a2 = colorTuple.secondary?.toArgb().let {
                if (it != null) {
                    TonalPalette.fromInt(it)
                } else {
                    TonalPalette.fromHueAndChroma(hue, hct.secondaryChroma())
                }
            }

            val a3 = colorTuple.tertiary?.toArgb().let {
                if (it != null) {
                    TonalPalette.fromInt(it)
                } else {
                    TonalPalette.fromHueAndChroma(hct.tertiaryHue(), hct.tertiaryChroma())
                }
            }

            val n1 = colorTuple.surface?.toArgb().let {
                if (it != null) {
                    TonalPalette.fromInt(it)
                } else {
                    TonalPalette.fromHueAndChroma(hue, (chroma / 12.0).coerceAtMost(4.0))
                }
            }

            val n2 = TonalPalette.fromInt(n1.tone(90))

            val nv = colorTuple.neutralVariant?.toArgb().let {
                if (it != null) {
                    TonalPalette.fromInt(it)
                } else {
                    n2
                }
            }

            val e = colorTuple.error?.toArgb()?.let(TonalPalette::fromInt)

            val scheme = when (style) {
                PaletteStyle.TonalSpot -> DynamicScheme(
                    sourceColorHct = hct,
                    variant = Variant.TONAL_SPOT,
                    isDark = isDarkTheme,
                    contrastLevel = contrastLevel,
                    primaryPalette = a1,
                    secondaryPalette = a2,
                    tertiaryPalette = a3,
                    neutralPalette = n1,
                    neutralVariantPalette = nv,
                    errorPalette = e ?: TonalPalette.fromHueAndChroma(25.0, 84.0),
                )

                PaletteStyle.Neutral -> SchemeNeutral(hct, isDarkTheme, contrastLevel)
                PaletteStyle.Vibrant -> SchemeVibrant(hct, isDarkTheme, contrastLevel)
                PaletteStyle.Expressive -> SchemeExpressive(hct, isDarkTheme, contrastLevel)
                PaletteStyle.Rainbow -> SchemeRainbow(hct, isDarkTheme, contrastLevel)
                PaletteStyle.FruitSalad -> SchemeFruitSalad(hct, isDarkTheme, contrastLevel)
                PaletteStyle.Monochrome -> SchemeMonochrome(hct, isDarkTheme, contrastLevel)
                PaletteStyle.Fidelity -> SchemeFidelity(hct, isDarkTheme, contrastLevel)
                PaletteStyle.Content -> SchemeContent(hct, isDarkTheme, contrastLevel)
            }

            scheme.toColorScheme(isAmoled = false)
                .withDynamicErrorColors(style, isDarkTheme, colorTuple.error)
                .withExpressiveOnColors(isDarkTheme)
        }

    return colorScheme
        .withContrastingSurfaces(
            surfaceSeed = colorTuple.surface,
            isDarkTheme = isDarkTheme,
        )
        .toAmoled(amoledMode && isDarkTheme)
        .invertColors(isInvertColors && !dynamicColor)
        .run {
            copy(
                outlineVariant = onSecondaryContainer
                    .copy(alpha = 0.2f)
                    .compositeOverSurface(surfaceColorAtElevation(6.dp)),
                background = surface,
            )
        }
}

/** 浅色模式下按表面种子色重推 surface 层级（ImageToolbox withContrastingSurfaces）。 */
private fun ColorScheme.withContrastingSurfaces(
    surfaceSeed: Color?,
    isDarkTheme: Boolean,
): ColorScheme {
    if (isDarkTheme) return this

    val seed = Hct.fromInt((surfaceSeed ?: surface).toArgb())

    fun surfaceColor(tone: Int, chromaMultiplier: Double = 1.0): Color {
        val chroma = (seed.chroma * chromaMultiplier).coerceAtMost(12.0)
        return Color(TonalPalette.fromHueAndChroma(seed.hue, chroma).tone(tone))
    }

    return copy(
        surface = surfaceColor(97),
        surfaceBright = surfaceColor(99),
        surfaceDim = surfaceColor(82, 1.8),
        surfaceContainerLowest = surfaceColor(100),
        surfaceContainerLow = surfaceColor(94, 1.2),
        surfaceContainer = surfaceColor(91, 1.4),
        surfaceContainerHigh = surfaceColor(88, 1.6),
        surfaceContainerHighest = surfaceColor(85, 1.8),
        surfaceVariant = surfaceColor(85, 1.8),
    )
}

/** 错误色按角色 tone 固定并掺入主色（ImageToolbox withDynamicErrorColors）。 */
private fun ColorScheme.withDynamicErrorColors(
    style: PaletteStyle,
    isDarkTheme: Boolean,
    errorSeed: Color?,
): ColorScheme {
    val errorHue = errorSeed?.toArgb()?.let { Hct.fromInt(it).hue }
        ?: Hct.fromInt(primary.toArgb()).dynamicErrorHue()
    val palette = TonalPalette.fromHueAndChroma(
        hue = errorHue,
        chroma = style.dynamicErrorChroma(),
    )
    val errorTone = if (isDarkTheme) 70 else 30
    val onErrorTone = if (isDarkTheme) 22 else 84
    val errorContainerTone = if (isDarkTheme) 30 else 78
    val onErrorContainerTone = if (isDarkTheme) 84 else 22

    return copy(
        error = Color(palette.tone(errorTone)).blend(primary, 0.15f),
        onError = Color(palette.tone(onErrorTone)),
        errorContainer = Color(palette.tone(errorContainerTone)).blend(primaryContainer, 0.15f),
        onErrorContainer = Color(palette.tone(onErrorContainerTone)),
    )
}

/** on* 文字色选对比度 >= 4.5 的最浅/最深 tone（ImageToolbox withExpressiveOnColors）。 */
private fun ColorScheme.withExpressiveOnColors(isDarkTheme: Boolean): ColorScheme = copy(
    onPrimary = primary.expressiveOnColor(isDarkTheme) ?: onPrimary,
    onSecondary = secondary.expressiveOnColor(isDarkTheme) ?: onSecondary,
    onTertiary = tertiary.expressiveOnColor(isDarkTheme) ?: onTertiary,
)

private fun Color.expressiveOnColor(isDarkTheme: Boolean): Color? {
    val palette = TonalPalette.fromInt(toArgb())
    val candidateTones = if (isDarkTheme) {
        listOf(22, 21, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10)
    } else {
        listOf(96, 97, 98)
    }

    return candidateTones
        .asSequence()
        .map { Color(palette.tone(it)) }
        .firstOrNull { candidate ->
            ColorUtils.calculateContrast(candidate.toArgb(), toArgb()) >= 4.5
        }
}

private fun ColorScheme.invertColors(enabled: Boolean): ColorScheme {
    fun Color.invertColor(): Color = if (enabled) {
        Color(this.toArgb() xor 0x00ffffff)
    } else {
        this
    }

    return this.copy(
        primary = primary.invertColor(),
        onPrimary = onPrimary.invertColor(),
        primaryContainer = primaryContainer.invertColor(),
        onPrimaryContainer = onPrimaryContainer.invertColor(),
        inversePrimary = inversePrimary.invertColor(),
        secondary = secondary.invertColor(),
        onSecondary = onSecondary.invertColor(),
        secondaryContainer = secondaryContainer.invertColor(),
        onSecondaryContainer = onSecondaryContainer.invertColor(),
        tertiary = tertiary.invertColor(),
        onTertiary = onTertiary.invertColor(),
        tertiaryContainer = tertiaryContainer.invertColor(),
        onTertiaryContainer = onTertiaryContainer.invertColor(),
        background = background.invertColor(),
        onBackground = onBackground.invertColor(),
        surface = surface.invertColor(),
        onSurface = onSurface.invertColor(),
        surfaceVariant = surfaceVariant.invertColor(),
        onSurfaceVariant = onSurfaceVariant.invertColor(),
        surfaceTint = surfaceTint.invertColor(),
        inverseSurface = inverseSurface.invertColor(),
        inverseOnSurface = inverseOnSurface.invertColor(),
        error = error.invertColor(),
        onError = onError.invertColor(),
        errorContainer = errorContainer.invertColor(),
        onErrorContainer = onErrorContainer.invertColor(),
        outline = outline.invertColor(),
        outlineVariant = outlineVariant.invertColor(),
        surfaceBright = surfaceBright.invertColor(),
        surfaceDim = surfaceDim.invertColor(),
        surfaceContainer = surfaceContainer.invertColor(),
        surfaceContainerHigh = surfaceContainerHigh.invertColor(),
        surfaceContainerHighest = surfaceContainerHighest.invertColor(),
        surfaceContainerLow = surfaceContainerLow.invertColor(),
        surfaceContainerLowest = surfaceContainerLowest.invertColor(),
        primaryFixed = primaryFixed.invertColor(),
        primaryFixedDim = primaryFixedDim.invertColor(),
        onPrimaryFixed = onPrimaryFixed.invertColor(),
        onPrimaryFixedVariant = onPrimaryFixedVariant.invertColor(),
        secondaryFixed = secondaryFixed.invertColor(),
        secondaryFixedDim = secondaryFixedDim.invertColor(),
        onSecondaryFixed = onSecondaryFixed.invertColor(),
        onSecondaryFixedVariant = onSecondaryFixedVariant.invertColor(),
        tertiaryFixed = tertiaryFixed.invertColor(),
        tertiaryFixedDim = tertiaryFixedDim.invertColor(),
        onTertiaryFixed = onTertiaryFixed.invertColor(),
        onTertiaryFixedVariant = onTertiaryFixedVariant.invertColor(),
    )
}

/** AMOLED 纯黑（ImageToolbox toAmoled）。 */
private fun ColorScheme.toAmoled(amoledMode: Boolean): ColorScheme {
    fun Color.darken(fraction: Float = 0.5f): Color =
        Color(toArgb().blend(Color.Black.toArgb(), fraction))

    return if (amoledMode) {
        copy(
            primary = primary.darken(0.3f),
            onPrimary = onPrimary.darken(0.1f),
            primaryContainer = primaryContainer.darken(0.3f),
            onPrimaryContainer = onPrimaryContainer.darken(0.1f),
            inversePrimary = inversePrimary.darken(0.3f),
            secondary = secondary.darken(0.3f),
            onSecondary = onSecondary.darken(0.1f),
            secondaryContainer = secondaryContainer.darken(0.3f),
            onSecondaryContainer = onSecondaryContainer.darken(0.1f),
            tertiary = tertiary.darken(0.3f),
            onTertiary = onTertiary.darken(0.1f),
            tertiaryContainer = tertiaryContainer.darken(0.3f),
            onTertiaryContainer = onTertiaryContainer.darken(0.1f),
            background = Color.Black,
            onBackground = onBackground.darken(0.1f),
            surface = Color.Black,
            onSurface = onSurface.darken(0.1f),
            surfaceVariant = surfaceVariant.darken(0.1f),
            onSurfaceVariant = onSurfaceVariant.darken(0.1f),
            surfaceTint = surfaceTint,
            inverseSurface = inverseSurface.darken(),
            inverseOnSurface = inverseOnSurface.darken(0.1f),
            error = error.darken(0.3f),
            onError = onError.darken(0.1f),
            errorContainer = errorContainer.darken(0.3f),
            onErrorContainer = onErrorContainer.darken(0.1f),
            outline = outline.darken(0.2f),
            outlineVariant = outlineVariant.darken(0.2f),
            scrim = scrim.darken(),
            surfaceBright = surfaceBright.darken(),
            surfaceDim = surfaceDim.darken(),
            surfaceContainer = surfaceContainer.darken(),
            surfaceContainerHigh = surfaceContainerHigh.darken(),
            surfaceContainerHighest = surfaceContainerHighest.darken(),
            surfaceContainerLow = surfaceContainerLow.darken(),
            surfaceContainerLowest = surfaceContainerLowest.darken(),
            primaryFixed = primaryFixed.darken(0.3f),
            primaryFixedDim = primaryFixedDim.darken(0.3f),
            onPrimaryFixed = onPrimaryFixed.darken(0.1f),
            onPrimaryFixedVariant = onPrimaryFixedVariant.darken(0.1f),
            secondaryFixed = secondaryFixed.darken(0.3f),
            secondaryFixedDim = secondaryFixedDim.darken(0.3f),
            onSecondaryFixed = onSecondaryFixed.darken(0.1f),
            onSecondaryFixedVariant = onSecondaryFixedVariant.darken(0.1f),
            tertiaryFixed = tertiaryFixed.darken(0.3f),
            tertiaryFixedDim = tertiaryFixedDim.darken(0.3f),
            onTertiaryFixed = onTertiaryFixed.darken(0.1f),
            onTertiaryFixedVariant = onTertiaryFixedVariant.darken(0.1f),
        )
    } else {
        this
    }
}

private fun Int.blend(color: Int, fraction: Float = 0.5f): Int =
    ColorUtils.blendARGB(this, color, fraction)

/** 标准合成（对应 ImageToolbox compositeOverSafe；本引擎所有颜色均已指定）。 */
private fun Color.compositeOverSurface(under: Color): Color = compositeOver(under)

/**
 * 方案变更时逐色动画过渡（ImageToolbox animateAllColors），
 * ImageToolbox 默认 tween(400)。
 */
@Composable
fun ColorScheme.animateAllColors(animationSpec: AnimationSpec<Color> = tween(400)): ColorScheme {
    @Composable
    fun Color.animateColor() = animateColorAsState(this, animationSpec).value

    return this.copy(
        primary = primary.animateColor(),
        onPrimary = onPrimary.animateColor(),
        primaryContainer = primaryContainer.animateColor(),
        onPrimaryContainer = onPrimaryContainer.animateColor(),
        inversePrimary = inversePrimary.animateColor(),
        secondary = secondary.animateColor(),
        onSecondary = onSecondary.animateColor(),
        secondaryContainer = secondaryContainer.animateColor(),
        onSecondaryContainer = onSecondaryContainer.animateColor(),
        tertiary = tertiary.animateColor(),
        onTertiary = onTertiary.animateColor(),
        tertiaryContainer = tertiaryContainer.animateColor(),
        onTertiaryContainer = onTertiaryContainer.animateColor(),
        background = background.animateColor(),
        onBackground = onBackground.animateColor(),
        surface = surface.animateColor(),
        onSurface = onSurface.animateColor(),
        surfaceVariant = surfaceVariant.animateColor(),
        onSurfaceVariant = onSurfaceVariant.animateColor(),
        surfaceTint = surfaceTint.animateColor(),
        inverseSurface = inverseSurface.animateColor(),
        inverseOnSurface = inverseOnSurface.animateColor(),
        error = error.animateColor(),
        onError = onError.animateColor(),
        errorContainer = errorContainer.animateColor(),
        onErrorContainer = onErrorContainer.animateColor(),
        outline = outline.animateColor(),
        outlineVariant = outlineVariant.animateColor(),
        scrim = scrim.animateColor(),
        surfaceBright = surfaceBright.animateColor(),
        surfaceDim = surfaceDim.animateColor(),
        surfaceContainer = surfaceContainer.animateColor(),
        surfaceContainerHigh = surfaceContainerHigh.animateColor(),
        surfaceContainerHighest = surfaceContainerHighest.animateColor(),
        surfaceContainerLow = surfaceContainerLow.animateColor(),
        surfaceContainerLowest = surfaceContainerLowest.animateColor(),
        primaryFixed = primaryFixed.animateColor(),
        primaryFixedDim = primaryFixedDim.animateColor(),
        onPrimaryFixed = onPrimaryFixed.animateColor(),
        onPrimaryFixedVariant = onPrimaryFixedVariant.animateColor(),
        secondaryFixed = secondaryFixed.animateColor(),
        secondaryFixedDim = secondaryFixedDim.animateColor(),
        onSecondaryFixed = onSecondaryFixed.animateColor(),
        onSecondaryFixedVariant = onSecondaryFixedVariant.animateColor(),
        tertiaryFixed = tertiaryFixed.animateColor(),
        tertiaryFixedDim = tertiaryFixedDim.animateColor(),
        onTertiaryFixed = onTertiaryFixed.animateColor(),
        onTertiaryFixedVariant = onTertiaryFixedVariant.animateColor(),
    )
}
