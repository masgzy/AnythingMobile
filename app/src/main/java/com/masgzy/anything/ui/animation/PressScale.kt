package com.masgzy.anything.ui.animation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 按压弹性缩放（alpha22，借自 KernelSU/SukiSU 管理器卡片的按压手感）：
 * 按下时轻微缩小 + spring 回弹。调用方自持 [MutableInteractionSource]
 * 传给 clickable/combinedClickable，再把同一 source 交给本修饰符：
 *
 * ```
 * val interaction = remember { MutableInteractionSource() }
 * Modifier
 *     .pressScale(interaction)
 *     .combinedClickable(interactionSource = interaction, indication = LocalIndication.current, ...)
 * ```
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.98f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
