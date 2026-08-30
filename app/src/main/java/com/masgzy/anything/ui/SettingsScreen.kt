package com.masgzy.anything.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masgzy.anything.AppViewModel
import com.masgzy.anything.data.ScanPhase
import com.masgzy.anything.data.SeedPalette
import com.masgzy.anything.data.ThemeMode

/**
 * 设置页 —— 外观面板对齐 ImageToolbox：
 *   主题模式（跟随系统/浅色/深色）、动态取色开关、主题色板、AMOLED 纯黑；
 * 功能面板：自动更新索引、立即更新索引、重建索引、结果排序、欢迎页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onShowWelcome: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val repoState by viewModel.repo.state.collectAsStateWithLifecycle()
    val scanning = repoState.phase != ScanPhase.IDLE
    val monetAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("外观") {
                // 主题模式（原版 Anything"夜间模式"三态 + ImageToolbox 同款）
                SettingLabel(Icons.Filled.Brightness6, "主题模式")
                val modes = listOf(
                    ThemeMode.SYSTEM to "跟随系统",
                    ThemeMode.LIGHT to "浅色",
                    ThemeMode.DARK to "深色",
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = {
                                viewModel.updateSettings { s -> s.copy(themeMode = mode) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) { Text(label) }
                    }
                }

                // 动态取色（Monet）：仅 Android 12+ 有效
                if (monetAvailable) {
                    SwitchSetting(
                        icon = Icons.Filled.Palette,
                        title = "动态取色",
                        subtitle = "跟随系统壁纸配色（Material You）",
                        checked = settings.dynamicColor,
                        onChange = {
                            viewModel.updateSettings { s -> s.copy(dynamicColor = it) }
                        },
                    )
                }

                // 主题色板（关闭动态取色或低版本系统时生效）
                SettingLabelWithTrailing(
                    icon = Icons.Filled.Contrast,
                    title = "主题色",
                    trailing = if (settings.dynamicColor && monetAvailable) "动态取色已启用" else "静态配色",
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(SeedPalette.entries) { (color, name) ->
                        val isSel = settings.seedColor == color
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                shape = CircleShape,
                                color = Color(color),
                                modifier = Modifier
                                    .size(38.dp)
                                    .clickable {
                                        viewModel.updateSettings { s -> s.copy(seedColor = color) }
                                    },
                            ) {}
                            Spacer(Modifier.height(4.dp))
                            Text(
                                name,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSel) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))

                // AMOLED 纯黑
                SwitchSetting(
                    icon = Icons.Filled.Tonality,
                    title = "AMOLED 纯黑",
                    subtitle = "深色模式下使用纯黑背景，省电护眼",
                    checked = settings.amoled,
                    onChange = { viewModel.updateSettings { s -> s.copy(amoled = it) } },
                )
            }

            SectionCard("索引") {
                SwitchSetting(
                    icon = Icons.Filled.Sync,
                    title = "进入应用时自动更新索引",
                    subtitle = "增量扫描，仅处理有变动的文件",
                    checked = settings.autoScanOnEnter,
                    onChange = {
                        viewModel.updateSettings { s -> s.copy(autoScanOnEnter = it) }
                    },
                )
                Text(
                    if (scanning) repoState.statusText else "索引在进入应用时自动保持最新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.startScan(incremental = true) },
                        enabled = !scanning && repoState.ready,
                    ) { Text("立即更新索引") }
                    OutlinedButton(
                        onClick = { viewModel.startScan(incremental = false) },
                        enabled = !scanning && repoState.ready,
                    ) { Text("重建索引") }
                }
            }

            SectionCard("结果") {
                SettingLabel(Icons.Filled.SortByAlpha, "排序方式")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(true to "按名称", false to "按时间")
                    options.forEachIndexed { i, (byName, label) ->
                        SegmentedButton(
                            selected = settings.sortByName == byName,
                            onClick = {
                                viewModel.updateSettings { s -> s.copy(sortByName = byName) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(label) }
                    }
                }
            }

            SectionCard("其他") {
                SettingRow(
                    icon = Icons.Filled.Info,
                    title = "重新查看欢迎页",
                    subtitle = "了解 Anything 的核心功能",
                    onClick = {
                        viewModel.markWelcomeSeen()
                        onShowWelcome()
                    },
                )
                SettingRow(
                    icon = Icons.Filled.WbTwilight,
                    title = "版本",
                    subtitle = "1.0.0-alpha2 · Go 引擎 + Kotlin 外壳 · Apache-2.0",
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SettingLabel(icon: ImageVector, text: String) {
    Row(
        Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SettingLabelWithTrailing(
    icon: ImageVector,
    title: String,
    trailing: String,
) {
    Row(
        Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.size(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            trailing,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchSetting(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
