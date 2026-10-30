package com.masgzy.anything.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.masgzy.anything.data.DesignStyle
import com.masgzy.anything.ui.theme.LocalUiMode
import com.masgzy.anything.ui.theme.uiPalette

/**
 * 双风格自适应壳组件（KSU UiMode 双设计同构）：
 * 组件名带 App 前缀的这几个按 [LocalUiMode] 在
 * Material Design 3 与 Miuix（HyperOS 风格）之间派发 ——
 * 结构保持一致，差异只在「色源」与「顶栏几何」：
 *  - MATERIAL：M3 Scaffold/TopAppBar/Switch，material-kolor 色板；
 *  - MIUIX：miuix Scaffold/SmallTopAppBar/Switch，HyperOS 色板
 *    （颜色经 [com.masgzy.anything.ui.theme.uiPalette] 统一解析）。
 * 屏幕代码只写一份，风格切换零散分支。
 */

/** 页面骨架：M3 Scaffold 与 miuix Scaffold 派发（两者 content 语义一致）。 */
@Composable
fun AppScaffold(
    topBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    containerColor: Color = uiPalette().surface,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    if (LocalUiMode.current == DesignStyle.MIUIX) {
        top.yukonga.miuix.kmp.basic.Scaffold(
            modifier = modifier,
            topBar = topBar,
            bottomBar = bottomBar,
            containerColor = containerColor,
            content = content,
        )
    } else {
        androidx.compose.material3.Scaffold(
            modifier = modifier,
            topBar = topBar,
            bottomBar = bottomBar,
            containerColor = containerColor,
            content = content,
        )
    }
}

/**
 * 页面顶栏：普通标题页（设置/目录/日志/教程）双风格派发。
 * MIUIX 走 miuix SmallTopAppBar（HyperOS 顶栏几何与配色）；
 * MATERIAL 走 M3 TopAppBar。搜索页的自定义搜索框顶栏不在此列。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val p = uiPalette()
    if (LocalUiMode.current == DesignStyle.MIUIX) {
        top.yukonga.miuix.kmp.basic.SmallTopAppBar(
            title = title,
            modifier = modifier,
            color = p.surface,
            titleColor = p.onSurface,
            navigationIcon = navigationIcon ?: {},
            actions = { actions() },
        )
    } else {
        TopAppBar(
            title = { Text(title) },
            modifier = modifier,
            navigationIcon = { navigationIcon?.invoke() },
            actions = { actions() },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = p.surface,
                titleContentColor = p.onSurface,
                navigationIconContentColor = p.onSurface,
                actionIconContentColor = p.onSurface,
            ),
        )
    }
}

/** 开关：M3 Switch 与 miuix Switch（自带 HyperOS 触感反馈）派发。 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (LocalUiMode.current == DesignStyle.MIUIX) {
        top.yukonga.miuix.kmp.basic.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    } else {
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    }
}

/**
 * 单选弹窗（Miuix 风格）：HyperOS 观感的居中卡片弹层 ——
 * 圆角大卡 + 自绘单选点（不依赖 miuix preference 组件，零额外 API）。
 * MATERIAL 风格的 M3 AlertDialog 版本见 SettingsScreen.OptionsDialog。
 */
@Composable
fun <T> MiuixOptionsDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = uiPalette()
    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(24.dp),
            color = p.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = p.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 自绘单选点：选中 = 主色环 + 实心内点，未选中 = 灰环
                        val ring = if (selected == value) p.primary else p.outline
                        Box(
                            Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .border(2.dp, ring, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected == value) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(p.primary),
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = p.onSurface,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) { Text("取消", color = p.primary) }
            }
        }
    }
}
