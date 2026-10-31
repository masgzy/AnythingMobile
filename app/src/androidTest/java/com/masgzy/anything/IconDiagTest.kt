package com.masgzy.anything

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 图标空白回归诊断（alpha29）：
 * 在受控环境下并排渲染「手写 / 迁移产物」两组 vector，逐格截图像素统计，
 * 结果打进 logcat（CI 侧 grep IconDiag 收集）。不做硬断言 —— 只收集事实。
 *
 * 事实矩阵（截至本测试）：
 *  - release/debug 均空白（R8/资源收缩已排除）；
 *  - alpha27 代码同样空白（alpha28 改动已排除）；
 *  - 唯一手写 vector ic_design_style 正常（迁移产物系统性空白）。
 */
@RunWith(AndroidJUnit4::class)
class IconDiagTest {

    @get:Rule
    val rule = createAndroidComposeRule<com.masgzy.anything.DiagActivity>()

    private val cases = listOf(
        "design_style_hand" to R.drawable.ic_design_style,
        "brightness_6_gen" to R.drawable.ic_brightness_6,
        "v2_nocomment" to R.drawable.ic_diag_v2_nocomment,
        "v3_genfile_handpd" to R.drawable.ic_diag_v3_genfile_handpd,
        "v4_handfile_genpd" to R.drawable.ic_diag_v4_handfile_genpd,
        "v5_spaces" to R.drawable.ic_diag_v5_spaces,
    )

    @Test
    fun iconPixelMatrix() {
        rule.setContent {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // framework VectorDrawable 渲染对照（Android 原生 ImageView）
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        android.widget.ImageView(ctx).apply {
                            setImageResource(R.drawable.ic_brightness_6)
                            setColorFilter(0xFFFF0000.toInt())
                        }
                    },
                    modifier = Modifier.size(64.dp),
                )
                cases.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (name, res) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier
                                        .size(64.dp)
                                        .background(Color(0xFFEEEEEE)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painterResource(res),
                                        contentDescription = name,
                                        tint = Color(0xFFFF0000),
                                        modifier = Modifier.size(48.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        Thread.sleep(500)
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        // 网格：2 行 x 4 列，格子 64dp + 间距；按设备密度换算像素后逐格统计红色像素
        val density = rule.activity.resources.displayMetrics.density
        val cell = (64 * density).toInt()
        val gap = (8 * density).toInt()
        val pad = (8 * density).toInt()
        cases.chunked(4).forEachIndexed { r, rowCases ->
            rowCases.forEachIndexed { c, (name, _) ->
                val x0 = pad + c * (cell + gap)
                val y0 = pad + r * (cell + gap)
                val colored = countColored(bmp, x0, y0, cell)
                Log.i("IconDiag", "RESULT $name coloredPx=$colored")
            }
        }
        Log.i("IconDiag", "MATRIX DONE density=$density bmp=${bmp.width}x${bmp.height}")
    }

    /** 统计格子内非背景（非白/非浅灰）的像素数。 */
    private fun countColored(bmp: Bitmap, x0: Int, y0: Int, size: Int): Int {
        var n = 0
        val step = 2 // 采样步长，加速
        var y = y0 + 2
        while (y < y0 + size - 2 && y < bmp.height) {
            var x = x0 + 2
            while (x < x0 + size - 2 && x < bmp.width) {
                val px = bmp.getPixel(x, y)
                val rr = (px shr 16) and 0xFF
                val gg = (px shr 8) and 0xFF
                val bb = px and 0xFF
                // 背景白(255,255,255) 或浅灰(238,238,238)：与红色目标差异显著才算
                if (rr > 150 && gg < 120 && bb < 120) n++
                x += step
            }
            y += step
        }
        return n
    }
}
