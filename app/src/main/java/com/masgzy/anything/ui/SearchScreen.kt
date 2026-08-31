package com.masgzy.anything.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masgzy.anything.AppViewModel
import com.masgzy.anything.StoragePermissions
import com.masgzy.anything.data.ScanPhase
import com.masgzy.anything.data.UiHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 主界面导航目标（抽屉菜单）。 */
const val ROUTE_SETTINGS = "settings"
const val ROUTE_ABOUT = "about"
const val ROUTE_WELCOME = "welcome"

/**
 * 搜索主界面 —— 布局与交互对齐原版 Anything：
 * 主色 AppBar 内嵌搜索框、"文件名 / office 文档正文 / 目录名"三页签、
 * 底部筛选（默认仅 ∞ 所有与漏斗，点 ∞ 弹出类别钮，文字标签自动显示后超时消失）、
 * 长按多选删除、结果详情面板。
 *
 * 索引更新（对齐原版"融入顶部 UI"的做法）：进入应用自动增量扫描时，
 * 页签行下方推入一条全宽浅黄横幅（原版同款配色）：扫描中显示"更新索引中..."，
 * 完成后短暂显示"索引更新完成"再自动收起。无弹窗、无悬浮胶囊；
 * 横幅在布局流内推挤内容，首次建索引仍为全屏遮罩。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: AppViewModel,
    onRequestAllFiles: () -> Unit,
    onNavigate: (String) -> Unit,
    drawerState: DrawerState,
) {
    val context = LocalContext.current
    val repoState by viewModel.repo.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 3 })

    var query by remember { mutableStateOf("") }
    var hasAccess by remember { mutableStateOf(StoragePermissions.hasStorageAccess(context)) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var detailHit by remember { mutableStateOf<UiHit?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 各页签的类别筛选（与底部筛选圆钮双向同步）
    var nameFilter by remember { mutableStateOf(FileCategory.ALL) }
    var officeFilter by remember { mutableStateOf(FileCategory.ALL) }

    // ∞ 弹出状态：点"所有"展开类别钮（还原原版交互）
    var filterExpanded by remember { mutableStateOf(false) }

    val inSelectMode = selected.isNotEmpty()

    // 授权返回（ON_RESUME）后刷新权限状态。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = StoragePermissions.hasStorageAccess(context)
                viewModel.autoScanIfNeed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 索引状态横条（原版同款融入式，见文件头注释）：扫描中延迟 600ms 出现
    // （增量扫描无变动时一闪而过反而吵），完成后短暂显示再自动收起。
    var stripText by remember { mutableStateOf<String?>(null) }
    var shownSummary by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(repoState.lastSummary) {
        val s = repoState.lastSummary ?: return@LaunchedEffect
        // 避免从设置页返回等重组场景重复提示
        if (s == shownSummary) return@LaunchedEffect
        shownSummary = s
        stripText = if (s.cancelled) "索引更新已取消" else "索引更新完成"
        delay(2500)
        stripText = null
    }
    LaunchedEffect(repoState.phase) {
        if (repoState.phase == ScanPhase.UPDATING) {
            delay(600)
            if (repoState.phase == ScanPhase.UPDATING) stripText = "更新索引中..."
        }
    }

    // 多选模式下拦截返回键
    BackHandler(enabled = inSelectMode) { selected = emptySet() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                onSettings = { onNavigate(ROUTE_SETTINGS) },
                onWelcome = onNavigate,
                onAbout = { onNavigate(ROUTE_ABOUT) },
            )
        },
    ) {
        Scaffold(
            topBar = {
                // 顶栏切换带淡入淡出，避免生硬跳变
                AnimatedContent(
                    targetState = inSelectMode,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "topbar",
                ) { selecting ->
                    if (selecting) {
                        SelectionTopBar(
                            count = selected.size,
                            onClose = { selected = emptySet() },
                            onSelectAll = {
                                selected = repoState.hits.map { it.path }.toSet()
                            },
                            onDelete = { confirmDelete = true },
                        )
                    } else {
                        SearchTopBar(
                            query = query,
                            onQueryChange = {
                                query = it
                                viewModel.search(it)
                            },
                            onOpenDrawer = {
                                scope.launch { drawerState.open() }
                            },
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Column(Modifier.fillMaxSize()) {
                    // 引擎降级横幅
                    repoState.initError?.let { err -> ErrorBanner(err) }

                    // 权限引导（还原原版首次授权流程）
                    if (!hasAccess) {
                        PermissionCard(
                            onRequest = onRequestAllFiles,
                            onAppDetails = { viewModel.openAppDetails() },
                        )
                    }

                    PrimaryTabRow(
                        selectedTabIndex = pagerState.currentPage,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        // 原版 Anything 为 TabLayout 全宽白色下划线指示条，
                        // 对应 M3 SecondaryIndicator + tabIndicatorOffset(全 Tab 宽)
                        indicator = {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(pagerState.currentPage),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        },
                    ) {
                        listOf("文件名", "office 文档正文", "目录名").forEachIndexed { i, title ->
                            Tab(
                                selected = pagerState.currentPage == i,
                                onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                                text = { Text(title, maxLines = 1) },
                            )
                        }
                    }

                    // 索引状态横条（原版同款）：布局流内推挤内容，非悬浮层
                    androidx.compose.animation.AnimatedVisibility(
                        visible = stripText != null,
                        enter = fadeIn(tween(180)) + expandVertically(tween(200)),
                        exit = fadeOut(tween(220)) + shrinkVertically(tween(240)),
                    ) {
                        IndexStatusStrip(stripText.orEmpty())
                    }

                    HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                        val filter = if (page == 0) nameFilter else officeFilter
                        HitListPage(
                            page = page,
                            hits = repoState.hits,
                            query = repoState.query,
                            sortByName = settings.sortByName,
                            filter = filter,
                            selected = selected,
                            onItemClick = { hit ->
                                if (inSelectMode) {
                                    selected = toggleSelect(selected, hit.path)
                                } else detailHit = hit
                            },
                            onItemLongClick = { hit ->
                                selected = toggleSelect(selected, hit.path)
                            },
                        )
                    }
                }

                // 索引状态走 Tab 行下方的原版同款横幅（见 IndexStatusStrip），无悬浮胶囊/Snackbar

                // 底部筛选区（目录名页不显示，还原原版）
                if (pagerState.currentPage != 2 && repoState.phase != ScanPhase.FIRST_BUILD) {
                    FilterPanel(
                        page = pagerState.currentPage,
                        current = if (pagerState.currentPage == 0) nameFilter else officeFilter,
                        expanded = filterExpanded,
                        onToggleExpand = { filterExpanded = !filterExpanded },
                        onSelect = { c ->
                            if (pagerState.currentPage == 0) nameFilter = c else officeFilter = c
                        },
                        modifier = Modifier.align(Alignment.BottomCenter),
                        labelSeconds = settings.filterLabelSeconds,
                        sortLabel = if (settings.sortByName) "已按名称排序" else "已按时间排序",
                        onToggleSort = {
                            viewModel.updateSettings { s -> s.copy(sortByName = !s.sortByName) }
                        },
                    )
                }

                // 首次建索引全屏遮罩（原版 CreateIndexWidget 文案）
                AnimatedVisibility(
                    visible = repoState.phase == ScanPhase.FIRST_BUILD,
                    enter = fadeIn(tween(250)),
                    exit = fadeOut(tween(250)),
                ) {
                    FirstBuildOverlay(scanned = repoState.scanned)
                }
            }
        }
    }

    // 详情面板
    detailHit?.let { hit ->
        DetailSheet(
            hit = hit,
            onOpen = {
                if (hit.isFolder) viewModel.openFolder(hit.path) else viewModel.openFile(hit.path)
                detailHit = null
            },
            onShare = {
                viewModel.shareFile(hit.path)
                detailHit = null
            },
            onDelete = {
                viewModel.deleteItems(listOf(hit.path))
                detailHit = null
            },
            onDismiss = { detailHit = null },
        )
    }

    // 删除确认（原版文案）
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除") },
            text = { Text("确认要删除这 ${selected.size} 项文件吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteItems(selected.toList())
                    selected = emptySet()
                    confirmDelete = false
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

private fun toggleSelect(current: Set<String>, path: String): Set<String> =
    if (path in current) current - path else current + path

/** 单页结果列表。page: 0=文件名 1=office 2=目录。 */
@Composable
private fun HitListPage(
    page: Int,
    hits: List<UiHit>,
    query: String,
    sortByName: Boolean,
    filter: FileCategory,
    selected: Set<String>,
    onItemClick: (UiHit) -> Unit,
    onItemLongClick: (UiHit) -> Unit,
) {
    val visible = remember(hits, sortByName, page, filter) {
        val filtered = when (page) {
            0 -> hits.filter {
                it.matched == "name" && !it.isFolder &&
                    (filter.isAll || categoryOf(it.path) == filter)
            }
            1 -> hits.filter {
                it.matched == "content" && (filter.isAll || categoryOf(it.path) == filter)
            }
            else -> hits.filter { it.isFolder }
        }
        if (sortByName) {
            filtered.sortedWith(compareBy({ it.name.length }, { it.name.lowercase() }))
        } else {
            filtered.sortedByDescending { it.mtime }
        }
    }

    when {
        query.isBlank() -> EmptyHint(
            if (page == 1) "请输入查找关键字" else "请在上方搜索栏中输入搜索内容"
        )
        visible.isEmpty() -> EmptyHint("未找到匹配结果")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            item(key = "header-$page") {
                val header = when (page) {
                    0 -> "文件名中包含 \"$query\" 的文件有"
                    1 -> "正文中包含 \"$query\" 的文档有"
                    else -> "目录名中包含 \"$query\" 的目录有"
                }
                ResultHeader(header, visible.size)
            }
            items(visible, key = { it.path }) { hit ->
                HitItem(
                    hit = hit,
                    selected = hit.path in selected,
                    onClick = { onItemClick(hit) },
                    onLongClick = { onItemLongClick(hit) },
                )
            }
            item(key = "footer-$page") {
                Text(
                    "没有更多了",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 底部筛选区（还原原版交互）：
 * 默认只在右下角显示 ∞"所有"（抬高错落）与漏斗排序钮；
 * 点击 ∞ 后类别钮（视频/音乐/图片/文档 或 Excel/PPT/Word）
 * 从左侧滑出，选择后即时过滤结果；再点 ∞ 复位为所有并收回。
 *
 * 文字标签（用户定制交互）：展开后所有钮上方的文字标签自动显示，
 * labelSeconds 秒后自动消失；点击任意类别钮重新显示并重置计时。
 * labelSeconds：0=不自动显示，-1=常驻，>0=自动隐藏秒数（设置页可改）。
 */
@Composable
private fun FilterPanel(
    page: Int,
    current: FileCategory,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelect: (FileCategory) -> Unit,
    modifier: Modifier = Modifier,
    labelSeconds: Int,
    sortLabel: String,
    onToggleSort: () -> Unit,
) {
    val options = if (page == 0) {
        listOf(FileCategory.VIDEO, FileCategory.AUDIO, FileCategory.IMAGE, FileCategory.DOC)
    } else {
        // 顺序还原原版 office 页：Excel / PPT / Word
        listOf(FileCategory.EXCEL, FileCategory.PPT, FileCategory.WORD)
    }

    // 标签自动显隐：展开即显示（常驻除外）→ 超时消失；labelTick 变化=点击重显+重置计时
    var labelsShown by remember { mutableStateOf(false) }
    var labelTick by remember { mutableStateOf(0) }
    LaunchedEffect(expanded, labelSeconds, labelTick) {
        if (!expanded) {
            labelsShown = false
            return@LaunchedEffect
        }
        when {
            labelSeconds == -1 -> labelsShown = true
            labelSeconds > 0 -> {
                labelsShown = true
                delay(labelSeconds * 1000L)
                labelsShown = false
            }
            // 0 = 不自动显示
            else -> labelsShown = false
        }
    }

    Box(modifier.fillMaxWidth()) {
        // 类别钮：点"所有"后弹出，滑入/淡入动画；文字标签随 labelsShown 显隐
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(220)) +
                slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 2 },
            exit = fadeOut(tween(160)) +
                slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp, bottom = 12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                options.forEach { opt ->
                    FilterButton(
                        label = opt.label,
                        icon = opt.icon,
                        selected = current == opt,
                        labelVisible = labelsShown,
                        onClick = {
                            // 点击重显标签并重置自动隐藏计时
                            labelTick++
                            onSelect(if (current == opt) FileCategory.ALL else opt)
                        },
                    )
                }
            }
        }

        // 右侧：∞ 所有（抬高）+ 漏斗排序，还原原版错落布局
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FilterButton(
                label = "所有",
                icon = Icons.Rounded.AllInclusive,
                selected = current.isAll,
                size = 56.dp,
                labelVisible = labelsShown,
                onClick = {
                    if (!current.isAll) onSelect(FileCategory.ALL)
                    onToggleExpand()
                },
            )
            Spacer(Modifier.height(12.dp))
            FilterButton(
                icon = Icons.Rounded.FilterList,
                selected = false,
                size = 44.dp,
                // 点击后浮现的排序提示气泡（操作确认，固定短时长）
                label = sortLabel,
                onClick = onToggleSort,
            )
        }
    }
}

/** 普通态顶栏：汉堡 + 放大镜 + 搜索输入（原版样式，M3 配色）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenDrawer: () -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Rounded.Menu, "菜单")
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 17.sp,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onPrimary),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    "请输入查询关键词",
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                                    fontSize = 17.sp,
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/** 多选态顶栏："已选择 X 项" + 全选/删除（原版多选文案）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "取消选择") }
        },
        title = { Text("已选择 $count 项") },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.DoneAll, "全选") }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "删除") }
        },
    )
}

/**
 * 抽屉（还原原版结构，修正宽度与布局）：
 *  - 宽度 300dp（M3 默认 360dp 在多数手机上接近全屏）；
 *  - 菜单在上，"Made with ❤"署名固定在底部；
 *  - 不再展示技术栈标签（用户不需要关心实现语言）。
 */
@Composable
private fun AppDrawer(
    onSettings: () -> Unit,
    onWelcome: (String) -> Unit,
    onAbout: () -> Unit,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    ModalDrawerSheet(
        modifier = Modifier.width(minOf(300.dp, screenWidth - 56.dp)),
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Text("Anything", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "search everything on Android",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                NavigationDrawerItem(
                    icon = { Icon(Icons.Rounded.Settings, null) },
                    label = { Text("设置") },
                    selected = false,
                    onClick = onSettings,
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.AutoMirrored.Rounded.Help, null) },
                    label = { Text("欢迎页") },
                    selected = false,
                    onClick = { onWelcome(ROUTE_WELCOME) },
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Rounded.Info, null) },
                    label = { Text("关于") },
                    selected = false,
                    onClick = onAbout,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "Made with ❤ by AnythingMobile",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
}

/** 降级横幅：引擎不可用时展示。 */
@Composable
private fun ErrorBanner(err: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(
            err,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** 权限引导卡片。 */
@Composable
private fun PermissionCard(onRequest: () -> Unit, onAppDetails: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "需要\"所有文件访问\"权限才能建立全盘索引",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRequest) { Text("去授权") }
                TextButton(onClick = onAppDetails) { Text("打开应用详情") }
            }
        }
    }
}

/**
 * 索引状态横条（原版同款）：页签行下方的全宽浅黄横幅，融入顶部 UI 布局流，
 * 推挤内容而非悬浮；配色取自原版实测（底 #FFFDE8 / 字 #ECB763 采自原版截图），
 * 深色主题下换深底亮字避免刺眼。
 */
@Composable
private fun IndexStatusStrip(text: String) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Surface(
        color = if (dark) Color(0xFF32301F) else Color(0xFFFFFDE8),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(42.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (dark) Color(0xFFF2C368) else Color(0xFFE6AC4E),
            )
        }
    }
}

/** 首次建索引全屏遮罩（文案还原原版 CreateIndexWidget）。 */
@Composable
private fun FirstBuildOverlay(scanned: Long) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )
            Spacer(Modifier.height(28.dp))
            Text(
                "首次使用，正在为您建立文件索引",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "正在为您建立 office 文档全文索引\n当前扫描到文件数 $scanned",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "所需时间由您设备上的文档个数决定，该过程可能需要持续数分钟\n仅首次启动需要创建索引，请您耐心等候\n请不要切换程序",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
