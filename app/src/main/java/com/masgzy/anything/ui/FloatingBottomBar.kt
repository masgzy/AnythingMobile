package com.masgzy.anything.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop

/**
 * 分页底栏（搜索 / 目录 / 设置三页切换）。
 *
 *  - 悬浮形态（showPaginator，默认开）：NPatch FloatingGlassBottomBar ——
 *    悬浮胶囊 + 指示器拖拽动效；液态玻璃开关（Android 13+）启用
 *    LiquidGlass 模式（vibrancy + blur + lens 折射，miuix-blur），
 *    关闭时为 None 模式（纯色悬浮胶囊 + 滑动指示器）；
 *  - 标准形态：通栏贴底（M3 风格，自绘）。
 *
 * 悬浮时页面内容延伸到底栏之下（玻璃取样需要），各页滚动列表
 * 用 [FLOATING_BAR_CONTENT_PADDING] 预留尾部空隙。
 */

/** 底栏三个入口（图标 + 标签）。 */
data class BottomBarItem(val label: String, val icon: ImageVector)

val BOTTOM_BAR_ITEMS = listOf(
    BottomBarItem("搜索", Icons.Rounded.Search),
    BottomBarItem("目录", Icons.Rounded.Folder),
    BottomBarItem("设置", Icons.Rounded.Settings),
)

/**
 * 悬浮底栏的实时占位高度（含底部导航栏 inset 与呼吸空隙）。
 * 照 NPatch MainScreen 的 LocalFloatingBottomBarPadding 机制：
 * MainShell 实测底栏高度后 provide，各页面滚动内容的尾部
 * contentPadding 消费它 —— 内容延伸到栏下（玻璃取样需要），
 * 但滚动到底时最后几项不被遮挡。
 */
val LocalFloatingBarPadding = staticCompositionLocalOf { 88.dp }

/** 兼容旧引用（无 CompositionLocal 环境时的保底值）。 */
internal val FLOATING_BAR_CONTENT_PADDING = 88.dp

/**
 * @param selected 当前选中 tab（0 搜索 / 1 目录 / 2 设置）
 * @param onSelect 切换回调
 * @param floating true=悬浮胶囊（NPatch 底栏）；false=通栏贴底
 * @param glass 液态玻璃（悬浮形态 + API 33+ 时生效；null backdrop 自动降级 None）
 * @param backdrop miuix-blur 页面层录制（glass=true 时由调用方提供）
 * @param onBarHeight 实测总占位高度回调（含 inset 与呼吸空隙，供 LocalFloatingBarPadding）
 */
@Composable
fun FloatingBottomBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    floating: Boolean,
    glass: Boolean,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    onBarHeight: (androidx.compose.ui.unit.Dp) -> Unit = {},
) {
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current

    if (floating) {
        Box(
            modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp + navInset),
            contentAlignment = Alignment.BottomCenter,
        ) {
            FloatingGlassBottomBar(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    // NPatch 公式：栏高 + 12dp 下边距 + 8dp 呼吸 + 导航栏 inset
                    .onSizeChanged {
                        onBarHeight(
                            with(density) { it.height.toDp() } + 12.dp + 8.dp + navInset,
                        )
                    },
                items = BOTTOM_BAR_ITEMS,
                selectedIndex = { selected },
                onSelected = onSelect,
                backdrop = backdrop,
                mode = if (glass && backdrop != null) {
                    FloatingBottomBarMode.LiquidGlass
                } else {
                    FloatingBottomBarMode.None
                },
                iconContent = { item, index, previewSelected ->
                    FloatingGlassBottomBarIcon(
                        selected = previewSelected || index == selected,
                        selectedIcon = item.icon,
                        unselectedIcon = item.icon,
                    )
                },
                labelContent = { item, index ->
                    FloatingGlassBottomBarLabel(
                        label = item.label,
                        selected = index == selected,
                    )
                },
            )
        }
    } else {
        // —— 通栏贴底（M3 风格自绘）——
        val cs = MaterialTheme.colorScheme
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .onSizeChanged {
                    onBarHeight(with(density) { it.height.toDp() } + 8.dp)
                },
            color = cs.surfaceContainer,
            contentColor = cs.onSurface,
            tonalElevation = 0.dp,
        ) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BOTTOM_BAR_ITEMS.forEachIndexed { i, item ->
                        StandardBarEntry(
                            item = item,
                            selected = i == selected,
                            onClick = { onSelect(i) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(navInset),
                )
            }
        }
    }
}

@Composable
private fun StandardBarEntry(
    item: BottomBarItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val tint = if (selected) cs.primary else cs.onSurfaceVariant
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .background(
                    if (selected) cs.primaryContainer.copy(alpha = 0.85f) else Color.Transparent,
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 18.dp, vertical = 4.dp),
        ) {
            Icon(item.icon, item.label, Modifier.size(24.dp), tint = tint)
        }
        Text(
            item.label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
