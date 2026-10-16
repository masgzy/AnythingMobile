package com.masgzy.anything.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** HSV 颜色表示（h: 0~360，s/v: 0~1）。 */
data class Hsv(val h: Float, val s: Float, val v: Float)

/** ARGB(Int) -> HSV。 */
fun Int.toHsv(): Hsv {
    val r = ((this shr 16) and 0xFF) / 255f
    val g = ((this shr 8) and 0xFF) / 255f
    val b = (this and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val h = when {
        delta <= 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0) it + 360f else it }
    val s = if (max <= 0f) 0f else delta / max
    return Hsv(h, s, max)
}

/** HSV -> Compose Color。 */
fun Hsv.toColor(): Color {
    val c = v * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - c
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

/** ARGB(Int) -> "#RRGGBB"。 */
fun hexOf(argb: Int): String = "#%06X".format((argb and 0xFFFFFF))

/** 解析 #RRGGBB / RRGGBB 十六进制颜色；非法输入返回 null。 */
fun parseHexColor(input: String): Int? {
    val hex = input.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
    if (hex.length != 6) return null
    val v = hex.toLongOrNull(16) ?: return null
    if (v < 0 || v > 0xFFFFFF) return null
    return (0xFF000000L or v).toInt()
}

/**
 * 饱和度-明度（SV）二维取色区（对齐 ImageToolbox 取色器）：
 * 横轴饱和度（白 → 当前色相纯色），纵轴明度（透明 → 黑），
 * 拖动/点按即选色，白色圆环为当前选点。
 */
@Composable
fun SvArea(
    hsv: Hsv,
    onChange: (Hsv) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 手势回调持最新状态，避免 pointerInput(Unit) 捕获到过期 hsv
    val currentHsv by rememberUpdatedState(hsv)
    val currentOnChange by rememberUpdatedState(onChange)
    var areaSize by remember { mutableStateOf(IntSize.Zero) }

    fun pick(pos: Offset) {
        if (areaSize.width <= 0 || areaSize.height <= 0) return
        val s = (pos.x / areaSize.width).coerceIn(0f, 1f)
        val v = 1f - (pos.y / areaSize.height).coerceIn(0f, 1f)
        currentOnChange(Hsv(currentHsv.h, s, v))
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .onSizeChanged { areaSize = it }
            .pointerInput(Unit) {
                detectTapGestures { pos -> pick(pos) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    pick(change.position)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pureHue = Hsv(currentHsv.h, 1f, 1f).toColor()
            drawRect(brush = Brush.horizontalGradient(listOf(Color.White, pureHue)))
            drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val cx = currentHsv.s * size.width
            val cy = (1f - currentHsv.v) * size.height
            drawCircle(Color.White, radius = 22f, center = Offset(cx, cy))
            drawCircle(
                Hsv(currentHsv.h, currentHsv.s, currentHsv.v).toColor(),
                radius = 17f,
                center = Offset(cx, cy),
            )
        }
    }
}

/**
 * 色相滑条：0~360 度彩虹渐变，拖动/点按调整色相，圆点指示当前色相。
 */
@Composable
fun HueSlider(
    hsv: Hsv,
    onChange: (Hsv) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentHsv by rememberUpdatedState(hsv)
    val currentOnChange by rememberUpdatedState(onChange)

    fun pick(x: Float, width: Float) {
        if (width <= 0f) return
        currentOnChange(currentHsv.copy(h = (x / width).coerceIn(0f, 1f) * 360f))
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(Unit) {
                detectTapGestures { pos -> pick(pos.x, size.width.toFloat()) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    pick(change.position.x, size.width.toFloat())
                }
            },
    ) {
        val rainbow = listOf(
            Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
            Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
        )
        drawRoundRect(
            brush = Brush.horizontalGradient(rainbow),
            cornerRadius = CornerRadius(size.height / 2f),
        )
        val cx = (currentHsv.h / 360f).coerceIn(0f, 1f) * size.width
        val cy = size.height / 2f
        drawCircle(Color.White, radius = size.height / 2f - 2f, center = Offset(cx, cy))
        drawCircle(
            Hsv(currentHsv.h, 1f, 1f).toColor(),
            radius = size.height / 2f - 5f,
            center = Offset(cx, cy),
        )
    }
}

/**
 * #RRGGBB 输入框：与取色区双向同步；仅放行十六进制字符。
 */
@Composable
fun HexColorField(
    hex: String,
    onHexChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = hex,
        onValueChange = { input ->
            onHexChange(input.filter { it in "0123456789abcdefABCDEF#" }.take(7))
        },
        label = { Text("#RRGGBB") },
        singleLine = true,
        isError = parseHexColor(hex) == null,
        modifier = modifier.fillMaxWidth(),
    )
}
