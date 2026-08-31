package com.masgzy.anything.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masgzy.anything.AppViewModel
import com.masgzy.anything.data.PalettePreset
import com.masgzy.anything.data.RoleColors
import com.masgzy.anything.data.RolePalette
import com.masgzy.anything.data.ScanPhase
import com.masgzy.anything.data.SeedPalette
import com.masgzy.anything.data.ThemeMode

/**
 * 设置页 —— 视觉对齐 ImageToolbox：每个设置项都是独立圆角卡片
 * （20dp 圆角、高表面色容器、左侧图标 + 标题/副标题 + 右侧控件）。
 *
 * 外观面板：主题模式三态、动态取色（Monet）、配色方案
 * （ImageToolbox 式角色化调色板：主色/辅助色/第三色/表面/中性变体/错误
 * 逐一编辑，附"简单变体"预设），AMOLED 纯黑；
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
    var showPaletteSheet by remember { mutableStateOf(false) }
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
                    subtitle = "如果启用，应用颜色将更改为壁纸颜色",
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
                subtitle = paletteSubtitle(settings, monetAvailable),
                onClick = { showPaletteSheet = true },
                leading = {
                    // 当前主色预览圆点
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(settings.roleColors.primary))
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
                title = "Amoled模式",
                subtitle = "如果启用，在夜间模式下背景色将设为纯黑",
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
                subtitle = "1.0.0-alpha4 · Apache-2.0",
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

    // ---- 配色方案（ImageToolbox 式角色化调色板） ----
    if (showPaletteSheet) {
        PaletteSheet(
            current = settings.roleColors,
            onSelectPreset = { preset ->
                viewModel.updateSettings { s ->
                    s.copy(
                        roleColors = preset.roles,
                        advancedPalette = true,
                        dynamicColor = false,
                    )
                }
            },
            onEditRole = { roles ->
                viewModel.updateSettings { s ->
                    s.copy(roleColors = roles, advancedPalette = true, dynamicColor = false)
                }
            },
            onReset = {
                viewModel.updateSettings { s ->
                    s.copy(
                        roleColors = RolePalette.DEFAULT_ROLES,
                        advancedPalette = false,
                    )
                }
            },
            onDismiss = { showPaletteSheet = false },
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

/** 配色方案卡片的副标题：说明当前外观来源（动态取色/预设/自定义）。 */
private fun paletteSubtitle(settings: com.masgzy.anything.data.AppSettings, monet: Boolean): String =
    when {
        settings.dynamicColor && monet ->
            "应用程序的主题将基于选择的颜色（当前为动态取色）"
        settings.advancedPalette ->
            RolePalette.matchPreset(settings.roleColors)?.name ?: "自定义角色配色"
        else ->
            "应用程序的主题将基于选择的颜色 · ${SeedPalette.nameOf(settings.seedColor)}"
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
    androidx.compose.material3.AlertDialog(
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

// ---- 配色方案（ImageToolbox 式调色板） ----

/** 一个可编辑的颜色角色定义。 */
private data class RoleDef(
    val key: String,
    val label: String,
    val get: (RoleColors) -> Int,
    val set: (RoleColors, Int) -> RoleColors,
)

/** 六个可编辑角色：主色/辅助色/第三色/表面/中性变体/错误（对齐 ImageToolbox）。 */
private val roleDefs = listOf(
    RoleDef("primary", "主色", { it.primary }, { r, c -> r.copy(primary = c) }),
    RoleDef("secondary", "辅助色", { it.secondary }, { r, c -> r.copy(secondary = c) }),
    RoleDef("tertiary", "第三色", { it.tertiary }, { r, c -> r.copy(tertiary = c) }),
    RoleDef("surface", "表面", { it.surface }, { r, c -> r.copy(surface = c) }),
    RoleDef("surfaceVariant", "中性变体", { it.surfaceVariant }, { r, c -> r.copy(surfaceVariant = c) }),
    RoleDef("error", "错误", { it.error }, { r, c -> r.copy(error = c) }),
)

/**
 * 配色方案底部弹层（对齐 ImageToolbox 配色方案界面）：
 *  - "简单变体"预设横排（三分色圆片，点选整套应用）；
 *  - 六个角色逐一展开编辑：SV 取色区 + 色相滑条 + #RRGGBB 输入；
 *  - 所有改动即时生效（实时预览），"保存"即关闭，"恢复默认"回到种子色方案。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaletteSheet(
    current: RoleColors,
    onSelectPreset: (PalettePreset) -> Unit,
    onEditRole: (RoleColors) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 本地草稿：每次编辑即时回调（实时预览），关闭即保存
    var roles by remember { mutableStateOf(current) }
    var expandedRole by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 标题
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Icon(
                    Icons.Filled.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "配色方案",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // 简单变体
            Text(
                "简单变体",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(RolePalette.presets) { preset ->
                    PresetChip(
                        preset = preset,
                        selected = RolePalette.matchPreset(roles) == preset,
                        onClick = {
                            roles = preset.roles
                            expandedRole = null
                            onSelectPreset(preset)
                        },
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            // 六个角色逐一编辑
            roleDefs.forEach { def ->
                RoleSection(
                    def = def,
                    roles = roles,
                    expanded = expandedRole == def.key,
                    onToggle = {
                        expandedRole = if (expandedRole == def.key) null else def.key
                    },
                    onColorChange = { argb ->
                        val next = def.set(roles, argb)
                        roles = next
                        onEditRole(next)
                    },
                )
            }

            Spacer(Modifier.height(4.dp))

            // 底部操作行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = {
                    roles = RolePalette.DEFAULT_ROLES
                    expandedRole = null
                    onReset()
                }) {
                    Text("恢复默认")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onDismiss) {
                    Text("保存")
                }
            }
        }
    }
}

/** "简单变体"预设圆片：主/辅/第三三色扇区（对齐 ImageToolbox 花瓣色板）。 */
@Composable
private fun PresetChip(
    preset: PalettePreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(
                color = Color(preset.roles.primary),
                startAngle = -90f, sweepAngle = 180f, useCenter = true,
            )
            drawArc(
                color = Color(preset.roles.secondary),
                startAngle = 90f, sweepAngle = 90f, useCenter = true,
            )
            drawArc(
                color = Color(preset.roles.tertiary),
                startAngle = 180f, sweepAngle = 90f, useCenter = true,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
        )
        if (selected) {
            val checkTint =
                if (Color(preset.roles.primary).luminance() > 0.5f) Color.Black else Color.White
            Icon(Icons.Filled.Check, contentDescription = "已选中", tint = checkTint)
        }
    }
}

/** 单个角色行：色块 + 名称 + 当前 hex + 展开箭头；展开内嵌取色器。 */
@Composable
private fun RoleSection(
    def: RoleDef,
    roles: RoleColors,
    expanded: Boolean,
    onToggle: () -> Unit,
    onColorChange: (Int) -> Unit,
) {
    val argb = def.get(roles)
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        def.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        hexOf(argb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
            ) {
                RoleColorEditor(argb = argb, onChange = onColorChange)
            }
        }
    }
}

/**
 * 单角色取色器：SV 取色区 + 色相滑条 + #RRGGBB 输入框。
 * 以 argb 为 remember key：任一途径改动成功后各输入源自动同步。
 */
@Composable
private fun RoleColorEditor(argb: Int, onChange: (Int) -> Unit) {
    var hsv by remember(argb) { mutableStateOf(argb.toHsv()) }
    var hex by remember(argb) { mutableStateOf(hexOf(argb)) }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        SvArea(hsv = hsv, onChange = { next ->
            hsv = next
            onChange(next.toColor().toArgb())
        })
        HueSlider(hsv = hsv, onChange = { next ->
            hsv = next
            onChange(next.toColor().toArgb())
        })
        HexColorField(hex = hex, onHexChange = { input ->
            hex = input
            parseHexColor(input)?.let { c ->
                hsv = c.toHsv()
                onChange(c)
            }
        })
    }
}
