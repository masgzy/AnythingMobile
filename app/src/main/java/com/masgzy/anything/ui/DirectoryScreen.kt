package com.masgzy.anything.ui

import com.masgzy.anything.R
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masgzy.anything.AppViewModel
import com.masgzy.anything.data.IndexScope
import com.masgzy.anything.data.IndexStats
import com.masgzy.anything.data.SafDirs
import com.masgzy.anything.data.ScanPhase
import com.masgzy.anything.data.ShizukuAccess

/**
 * 「目录」tab —— SAF 文件夹选择器授权的搜索目录管理（alpha19 重设计）。
 *
 * 相比 alpha18 的变化：
 *  - 顶部新增「怎么用」三步引导卡（添加目录 → 同步索引 → 去搜索），
 *    解决"看不懂这个功能咋用"的问题；
 *  - 「添加目录」从悬浮 FAB 改为内容流内的引导卡按钮 + 空态大按钮，
 *    消除 FAB 被悬浮底栏遮挡的问题；
 *  - 同步/重建期间显示实况进度卡（indeterminate 进度条 + 已收录计数
 *    + 状态文本 + 取消按钮）——引擎按文件计数回报进度，总数未知故为
 *    不确定式进度条；
 *  - 列表与说明改为 NPatch 分组连块卡片；滚动尾部消费
 *    [LocalFloatingBarPadding]（NPatch 动态占位方案）。
 *
 * 数据流参照 NPatch 的授权闭环（GPL-3.0，README 致谢）：
 * 裸 ACTION_OPEN_DOCUMENT_TREE 选择 → 回调里 takePersistableUriPermission
 * （READ|WRITE）→ uri 字符串持久化进 AppSettings.searchDirs；列表展示时
 * 校验授权有效性（重取权限 + exists() 探测），失效标记并可删除。
 *
 * 「同步索引」按搜索范围语义分流（见 AppViewModel.syncDirIndex）：
 * 可映射物理路径时走引擎常规扫描管线，否则 SAF 遍历 + Ingest 注入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryScreen(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val repoState by viewModel.repo.state.collectAsStateWithLifecycle()
    val scanning = repoState.phase != ScanPhase.IDLE
    val indexStats by viewModel.indexStats.collectAsStateWithLifecycle()
    val shizukuState by viewModel.shizukuState.collectAsStateWithLifecycle()
    val externalCount by viewModel.externalCount.collectAsStateWithLifecycle()

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_CANCELED) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            viewModel.addSearchDir(uri.toString())
        }
    }
    val launchPicker = {
        // 裸 Intent（NPatch 同款）：选择器返回带持久化授权的 tree URI
        pickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
        Unit
    }

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        TopAppBar(
            title = { Text("目录") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp, top = 4.dp,
                // 悬浮底栏实测占位：滚动到底时最后一项不被遮挡
                bottom = 24.dp + LocalFloatingBarPadding.current,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- 索引状态卡（KSU StatusCard 同款语言：主色调大卡 + 图标 + 状态行） ----
            item(key = "status") {
                IndexStatusCard(stats = indexStats, scanning = scanning)
            }

            // ---- 三步引导（解决"看不懂怎么用"） ----
            item(key = "guide") {
                GroupCard {
                    Text(
                        "怎么用",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
                    )
                    GuideStep(
                        step = "1",
                        icon = painterResource(R.drawable.ic_add),
                        title = "添加目录",
                        desc = "点下方按钮，在系统文件夹选择器里挑选想纳入搜索的文件夹并授权",
                    )
                    GuideStep(
                        step = "2",
                        icon = painterResource(R.drawable.ic_manage_search),
                        title = "同步索引",
                        desc = "点「同步选定目录」，应用会读取目录里的文件名与 Office 文档正文建索引",
                    )
                    GuideStep(
                        step = "3",
                        icon = painterResource(R.drawable.ic_search),
                        title = "去搜索",
                        desc = "切到「搜索」页输入关键词即可命中目录内文件；" +
                            "配合下方「索引范围」可切换全盘/限定范围",
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            // ---- 添加目录按钮（内容流内，不再用悬浮 FAB 避免被底栏遮挡） ----
            item(key = "add") {
                Button(
                    onClick = launchPicker,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_add), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("添加目录")
                }
            }

            // ---- 扫描进度实况卡（替代无反馈的静默扫描） ----
            if (scanning) {
                item(key = "progress") {
                    ScanProgressCard(
                        statusText = repoState.statusText,
                        scanned = repoState.scanned,
                        firstBuild = repoState.phase == ScanPhase.FIRST_BUILD,
                        onCancel = { viewModel.cancelScan() },
                    )
                }
            }

            // ---- 索引范围（alpha26 三模式，替代旧的“仅搜索选定目录”开关） ----
            item(key = "scope") {
                GroupCard {
                    ScopeRow(
                        title = "全盘 + 目录",
                        desc = "全盘扫描之外，同步时还经文件提供器收录下方授权目录\n中不可直接映射的虚拟目录（如部分应用私有下载区）",
                        selected = settings.indexScope == IndexScope.FULL_DISK_AND_DIRS,
                        onClick = { viewModel.setIndexScope(IndexScope.FULL_DISK_AND_DIRS) },
                    )
                    GroupDivider()
                    ScopeRow(
                        title = "仅目录",
                        desc = "只索引下方授权的目录，其余存储位置不参与搜索",
                        selected = settings.indexScope == IndexScope.DIRS_ONLY,
                        onClick = { viewModel.setIndexScope(IndexScope.DIRS_ONLY) },
                    )
                    GroupDivider()
                    ScopeRow(
                        title = "仅全盘",
                        desc = "扫描整个共享存储；下方目录仅作授权记录（历史默认）",
                        selected = settings.indexScope == IndexScope.FULL_DISK_ONLY,
                        onClick = { viewModel.setIndexScope(IndexScope.FULL_DISK_ONLY) },
                    )
                }
            }

            // ---- 目录列表（含 Android/data 特权条目：与添加目录同列的入口） ----
            if (settings.searchDirs.isEmpty() && !settings.scanAndroidData) {
                item(key = "empty") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_folder),
                            null,
                            Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("还没有纳入任何目录", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "点上方「添加目录」，在系统文件夹选择器中选择\n并授权；或打开下方 Android/data 条目。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                item(key = "dirs-title") {
                    SectionHeader(
                        when (settings.indexScope) {
                            IndexScope.DIRS_ONLY -> "已纳入目录（参与搜索）"
                            IndexScope.FULL_DISK_AND_DIRS -> "已纳入目录（补充收录）"
                            IndexScope.FULL_DISK_ONLY -> "已纳入目录（仅记录）"
                        },
                    )
                }
                item(key = "dirs") {
                    GroupCard {
                        settings.searchDirs.forEachIndexed { index, uriStr ->
                            if (index > 0) GroupDivider()
                            DirectoryRow(
                                uriStr = uriStr,
                                onRemove = { viewModel.removeSearchDir(uriStr) },
                            )
                        }
                        if (settings.searchDirs.isNotEmpty()) GroupDivider()
                        // Android/data：与普通目录同列的条目（alpha26 自设置页迁入）
                        AndroidDataRow(
                            enabled = settings.scanAndroidData,
                            shizukuStatus = shizukuState.status,
                            externalCount = externalCount,
                            onToggle = { viewModel.setAndroidDataEnabled(it) },
                            onRequestPermission = { viewModel.requestShizukuPermission() },
                            onOpenDownload = { viewModel.openShizukuDownload() },
                        )
                    }
                }
            }

            // ---- 同步操作区 ----
            item(key = "sync") {
                SectionHeader("索引操作")
            }
            item(key = "sync-buttons") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { viewModel.syncDirIndex() },
                        enabled = !scanning,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_manage_search), null, Modifier.size(18.dp))
                        Text(
                            when (settings.indexScope) {
                                IndexScope.DIRS_ONLY -> "同步选定目录"
                                else -> "重建全盘索引"
                            },
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.startScan(incremental = false) },
                        enabled = !scanning,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("增量扫描")
                    }
                }
            }

            // ---- 补充说明（收纳为可读小字） ----
            item(key = "hint") {
                GroupCard {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_help_outline),
                            null,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "授权信息保存在本机（系统级持久授权 + 目录列表），" +
                                "移除应用后失效。SAF 遍历通道每次同步为整体重建，" +
                                "目录内容量大时建议保持「所有文件访问」授权以走高速管线。" +
                                "索引没有条目数上限，收录量只取决于磁盘内容。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 单个目录行（GroupCard 内使用；授权有效性延迟探测，失效标红可移除）。 */
@Composable
private fun DirectoryRow(uriStr: String, onRemove: () -> Unit) {
    val context = LocalContext.current
    var valid by remember(uriStr) { mutableStateOf(true) }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = {
            Icon(
                if (valid) painterResource(R.drawable.ic_folder) else painterResource(R.drawable.ic_folder_off),
                null,
                tint = if (valid) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
        },
        headlineContent = { Text(SafDirs.displayName(uriStr)) },
        supportingContent = {
            Text(
                if (valid) {
                    SafDirs.treeToPath(context, uriStr) ?: SafDirs.subPath(uriStr)
                } else {
                    "授权已失效（目录被移动或权限被回收），请删除后重新添加"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(painterResource(R.drawable.ic_close), "移除", modifier = Modifier.size(18.dp))
            }
        },
    )
    // 首次进入列表时探测授权有效性（NPatch 用时校验模式）
    LaunchedEffect(uriStr) {
        valid = SafDirs.isValid(context, uriStr)
    }
}

/** 三选一的范围单选行（GroupCard 内使用：RadioButton + 标题/说明）。 */
@Composable
private fun ScopeRow(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = {
            RadioButton(selected = selected, onClick = onClick)
        },
        headlineContent = {
            Text(
                title,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = { Text(desc) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Android/data 特权收录条目（alpha26 自设置页"扫描 Android/data 目录"
 * 开关迁入，与"添加目录"同列）：开关即收录/移除；未授权时点按引导授权。
 */
@Composable
private fun AndroidDataRow(
    enabled: Boolean,
    shizukuStatus: ShizukuAccess.Status,
    externalCount: Int,
    onToggle: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenDownload: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = {
            Icon(
                painterResource(R.drawable.ic_folder_special),
                null,
                tint = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text("Android/data（应用数据目录）") },
        supportingContent = {
            Text(
                when {
                    enabled && shizukuStatus == ShizukuAccess.Status.GRANTED ->
                        "已收录 $externalCount 项 · 仅文件名不含全文"
                    enabled && shizukuStatus == ShizukuAccess.Status.NEED_PERMISSION ->
                        "已开启 · 点此发起 Shizuku / Stellar 授权"
                    enabled -> "已开启 · 但 Shizuku / Stellar 未运行，点此前往下载"
                    else -> "开启后经 Shizuku / Stellar 特权收录（Android 11+ 隔离区）"
                },
            )
        },
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    when {
                        !on -> onToggle(false)
                        shizukuStatus == ShizukuAccess.Status.GRANTED -> onToggle(true)
                        else -> {
                            // 未授权：先开启（状态记忆），再引导授权/下载
                            onToggle(true)
                            if (shizukuStatus == ShizukuAccess.Status.NOT_RUNNING) {
                                onOpenDownload()
                            } else {
                                onRequestPermission()
                            }
                        }
                    }
                },
            )
        },
    )
}

/** 三步引导行：数字圆徽 + 图标 + 标题/说明。 */
@Composable
private fun GuideStep(step: String, icon: androidx.compose.ui.graphics.painter.Painter, title: String, desc: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(28.dp),
        ) {
            androidx.compose.foundation.layout.Box(
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    step,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            icon,
            null,
            Modifier
                .size(22.dp)
                .padding(top = 3.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 扫描进度实况卡：不确定进度条 + 已扫描计数 + 状态文本 + 取消。 */
@Composable
private fun ScanProgressCard(
    statusText: String,
    scanned: Long,
    firstBuild: Boolean,
    onCancel: () -> Unit,
) {
    GroupCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                if (firstBuild) "正在建立索引" else "正在更新索引",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "$statusText · 已处理 $scanned 个文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.TextButton(onClick = onCancel) {
                    Text("取消", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ---- 与 SettingsScreen 同款 NPatch 分组组件（同包内复用形态） ----

/**
 * 索引状态卡（alpha22，借鉴 KernelSU/SukiSU 首页 StatusCard 的设计语言）：
 * 主色调大卡 + 状态图标 + 标题/副标题 + 底部信息行，替代原先是"盲区"的
 * 索引规模展示 —— 用户在这里一眼看到收录了多少文件、多少文档有全文、
 * 特权通道收录多少，以及最近一次索引更新时间。
 */
@Composable
private fun IndexStatusCard(stats: IndexStats?, scanning: Boolean) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 状态图标（KSU 状态卡同款圆形衬托）
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    modifier = Modifier.size(46.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (scanning) painterResource(R.drawable.ic_swap_horiz) else painterResource(R.drawable.ic_travel_explore),
                            null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        when {
                            scanning -> "正在更新索引…"
                            stats == null || stats.indexed <= 0L -> "尚未建立索引"
                            else -> "索引就绪"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        when {
                            scanning -> "完成后这里会显示最新规模"
                            stats == null || stats.indexed <= 0L ->
                                "回到搜索页授权后自动建立，或点下方「同步索引」"
                            else -> "已收录 ${stats.indexed} 项 · 文档全文 ${stats.docsIndexed} 篇"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            // 底部信息行：扩展收录 + 最近更新（KSU 状态卡下方信息 chips 同位）
            if (!scanning && stats != null && (stats.external > 0L || stats.finishedAt.isNotBlank())) {
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (stats.external > 0L) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painterResource(R.drawable.ic_folder_special),
                                null,
                                Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "扩展收录 ${stats.external} 项",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                    if (stats.finishedAt.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painterResource(R.drawable.ic_schedule),
                                null,
                                Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                            Spacer(Modifier.width(4.dp))
                            // RFC3339（2026-10-24T08:30:45+08:00）→ 直接取 HH:mm
                            val hm = stats.finishedAt
                                .substringAfter('T', "")
                                .takeIf { it.length >= 5 }
                                ?.substring(0, 5)
                            Text(
                                if (hm != null) "最近更新 $hm" else "索引已完成",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分组小标题。 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
    )
}

/** 分组连块卡片（NPatch Card 同构）。 */
@Composable
private fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/** 连块内分割线（左端缩进）。 */
@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}
