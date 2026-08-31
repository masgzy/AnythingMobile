package com.masgzy.anything.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masgzy.anything.AppViewModel
import com.masgzy.anything.data.ScanPhase
import com.masgzy.anything.data.SeedPalette
import com.masgzy.anything.data.ThemeMode

/**
 * 设置页 —— 视觉对齐 ImageToolbox：每个设置项都是独立圆角卡片
 * （20dp 圆角、高表面色容器、左侧图标 + 标题/副标题 + 右侧控件）。
 *
 * 外观面板：主题模式三态、动态取色（Monet）、配色方案
 * （ImageToolbox 同款 16 色预设 + 任意十六进制自定义色）、AMOLED 纯黑；
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

    var showThemeModeDialog by remember { mutableStateOf(false) }
    var showPaletteDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }

    val modeLabel = when (settings.themeMode) {
        ThemeMode.SYSTEM -> "跟随系统"
        ThemeMode.LIGHT -> "浅色"
        ThemeMode.DARK -> "深色"
    }
    val sortLabel = if (settings.sortByName) "按名称" else "按时间"

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
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---- 外观 ----
            SectionHeader("外观")

            PreferenceCard(
                icon = Icons.Filled.Brightness6,
                title = "主题模式",
                subtitle = modeLabel,
                onClick = { showThemeModeDialog = true },
            )

            if (monetAvailable) {
                PreferenceCard(
                    icon = Icons.Filled.Wallpaper,
                    title = "动态取色",
                    subtitle = "跟随系统壁纸配色（Material You）",
                    trailing = {
                        Switch(
                            checked = settings.dynamicColor,
                            onCheckedChange = {
                                viewModel.updateSettings { s -> s.copy(dynamicColor = it) }
                            },
                        )
                    },
                )
            }

            PreferenceCard(
                icon = Icons.Filled.Palette,
                title = "配色方案",
                subtitle = if (settings.dynamicColor && monetAvailable) {
                    "当前使用动态取色，关闭后生效 · ${SeedPalette.nameOf(settings.seedColor)}"
                } else {
                    SeedPalette.nameOf(settings.seedColor)
                },
                onClick = { showPaletteDialog = true },
                leading = {
                    // 当前种子色预览圆点
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(settings.seedColor))
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            ),
                    )
                },
            )

            PreferenceCard(
                icon = Icons.Filled.Tonality,
                title = "AMOLED 纯黑",
                subtitle = "深色模式下使用纯黑背景，省电护眼",
                trailing = {
                    Switch(
                        checked = settings.amoled,
                        onCheckedChange = {
                            viewModel.updateSettings { s -> s.copy(amoled = it) }
                        },
                    )
                },
            )

            // ---- 索引 ----
            SectionHeader("索引")

            PreferenceCard(
                icon = Icons.Filled.Sync,
                title = "进入应用时自动更新索引",
                subtitle = "增量扫描，仅处理有变动的文件",
                trailing = {
                    Switch(
                        checked = settings.autoScanOnEnter,
                        onCheckedChange = {
                            viewModel.updateSettings { s -> s.copy(autoScanOnEnter = it) }
                        },
                    )
                },
            )

            PreferenceCard(
                icon = Icons.Filled.Sync,
                title = "立即更新索引",
                subtitle = if (scanning) repoState.statusText else "增量检查文件变动",
                enabled = !scanning && repoState.ready,
                onClick = { viewModel.startScan(incremental = true) },
            )

            PreferenceCard(
                icon = Icons.Filled.RestartAlt,
                title = "重建索引",
                subtitle = if (scanning) repoState.statusText
                else "清空后重新扫描全部文件，耗时较长",
                enabled = !scanning && repoState.ready,
                onClick = { viewModel.startScan(incremental = false) },
            )

            // ---- 结果 ----
            SectionHeader("结果")

            PreferenceCard(
                icon = Icons.Filled.SortByAlpha,
                title = "排序方式",
                subtitle = sortLabel,
                onClick = { showSortDialog = true },
            )

            // ---- 其他 ----
            SectionHeader("其他")

            PreferenceCard(
                icon = Icons.Filled.WbTwilight,
                title = "重新查看欢迎页",
                subtitle = "了解 Anything 的核心功能",
                onClick = {
                    viewModel.markWelcomeSeen()
                    onShowWelcome()
                },
            )

            PreferenceCard(
                icon = Icons.Filled.Info,
                title = "版本",
                subtitle = "1.0.0-alpha3 · Apache-2.0",
            )
        }
    }

    // ---- 主题模式选择 ----
    if (showThemeModeDialog) {
        OptionsDialog(
            title = "主题模式",
            options = listOf(
                ThemeMode.SYSTEM to "跟随系统",
                ThemeMode.LIGHT to "浅色",
                ThemeMode.DARK to "深色",
            ),
            selected = settings.themeMode,
            onSelect = { mode ->
                viewModel.updateSettings { s -> s.copy(themeMode = mode) }
            },
            onDismiss = { showThemeModeDialog = false },
        )
    }

    // ---- 配色方案（ImageToolbox 式调色板 + 自定义色） ----
    if (showPaletteDialog) {
        PaletteDialog(
            currentSeed = settings.seedColor,
            onSelect = { argb ->
                viewModel.updateSettings { s -> s.copy(seedColor = argb) }
            },
            onDismiss = { showPaletteDialog = false },
        )
    }

    // ---- 排序方式 ----
    if (showSortDialog) {
        OptionsDialog(
            title = "排序方式",
            options = listOf(true to "按名称", false to "按时间"),
            selected = settings.sortByName,
            onSelect = { byName ->
                viewModel.updateSettings { s -> s.copy(sortByName = byName) }
            },
            onDismiss = { showSortDialog = false },
        )
    }
}

// ---- 通用组件 ----

/** 分组小标题。 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 6.dp, top = 4.dp),
    )
}

/**
 * ImageToolbox 式设置项卡片：
 * 20dp 圆角、高表面色容器，左侧图标（可选自定义 leading，
 * 如配色方案卡片的色块预览），中间标题/副标题，右侧控件。
 */
@Composable
private fun PreferenceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(20.dp)
    val clickMod = onClick?.let { o ->
        Modifier.clickable(enabled = enabled) { o() }
    } ?: Modifier
    val contentAlpha = if (enabled) 1f else 0.5f

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(clickMod),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .alpha(contentAlpha),
                contentAlignment = Alignment.Center,
            ) {
                if (leading != null) {
                    leading()
                } else {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.alpha(contentAlpha),
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alpha(contentAlpha),
                )
            }
            trailing?.invoke()
        }
    }
}

/** 单选弹窗（主题模式 / 排序方式共用）。 */
@Composable
private fun <T> OptionsDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected == value,
                            onClick = {
                                onSelect(value)
                                onDismiss()
                            },
                        )
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 调色板弹窗：ImageToolbox 同款 16 预设色 + 原版默认靛蓝，
 * 支持任意 #RRGGBB 自定义色；点选即时生效、实时预览。
 */
@Composable
private fun PaletteDialog(
    currentSeed: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var showCustomInput by remember { mutableStateOf(false) }
    var customHex by remember { mutableStateOf("") }
    val customColor = parseHexColor(customHex)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配色方案") },
        text = {
            Column {
                Text(
                    "选择主题种子色；开启动态取色时跟随壁纸，此处的选择在关闭后生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(SeedPalette.entries) { sw ->
                        val selectedNow = currentSeed == sw.argb
                        val checkTint =
                            if (Color(sw.argb).luminance() > 0.5f) Color.Black else Color.White
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(sw.argb))
                                .border(
                                    width = if (selectedNow) 3.dp else 1.dp,
                                    color = if (selectedNow) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                )
                                .clickable { onSelect(sw.argb) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selectedNow) {
                                Icon(Icons.Filled.Check, null, tint = checkTint)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (showCustomInput) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = customHex,
                            onValueChange = { customHex = it },
                            label = { Text("#RRGGBB") },
                            singleLine = true,
                            isError = customHex.isNotBlank() && customColor == null,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { customColor?.let(onSelect) },
                            enabled = customColor != null,
                        ) {
                            Text("应用")
                        }
                    }
                } else {
                    TextButton(onClick = { showCustomInput = true }) {
                        Icon(Icons.Filled.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text("自定义颜色")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

/** 解析 #RRGGBB / RRGGBB 十六进制颜色；非法输入返回 null。 */
private fun parseHexColor(input: String): Int? {
    val hex = input.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
    if (hex.length != 6) return null
    val v = hex.toLongOrNull(16) ?: return null
    if (v < 0 || v > 0xFFFFFF) return null
    return (0xFF000000L or v).toInt()
}
