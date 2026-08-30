package com.masgzy.anything.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.SolidColor
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
import kotlinx.coroutines.launch

/** 主界面导航目标（抽屉菜单）。 */
const val ROUTE_SETTINGS = "settings"
const val ROUTE_ABOUT = "about"
const val ROUTE_WELCOME = "welcome"

/**
 * 搜索主界面 —— 布局与交互对齐原版 Anything：
 * 主色 AppBar 内嵌搜索框、"文件名 / office 文档正文 / 目录名"三页签、
 * 底部类别筛选圆钮、长按多选删除、结果详情面板。
 *
 * 索引更新：进入应用自动增量扫描（MainActivity ON_RESUME 驱动 autoScanIfNeed），
 * 顶部横幅显示"更新索引中…"，完成后 Snackbar 提示"索引更新完成"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: AppViewModel,
    onRequestAllFiles: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val repoState by viewModel.repo.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val drawerState = androidx.compose.material3.rememberDrawerState(
        androidx.compose.material3.DrawerValue.Closed
    )
    val pagerState = rememberPagerState(pageCount = { 3 })

    var query by remember { mutableStateOf("") }
    var hasAccess by remember { mutableStateOf(StoragePermissions.hasStorageAccess(context)) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var detailHit by remember { mutableStateOf<UiHit?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 各页签的类别筛选（与底部筛选圆钮双向同步）
    var nameFilter by remember { mutableStateOf(FileCategory.ALL) }
    var officeFilter by remember { mutableStateOf(FileCategory.ALL) }

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

    // "索引更新完成"提示：每次扫描收尾触发一次（原版提示文案）。
    LaunchedEffect(repoState.lastSummary) {
        val s = repoState.lastSummary ?: return@LaunchedEffect
        val msg = when {
            s.cancelled -> "索引更新已取消"
            s.firstBuild -> "首次索引创建完成，共索引 ${s.files} 项"
            s.changed -> "索引更新完成：新增 ${s.added}，更新 ${s.updated}，移除 ${s.removed}"
            else -> "索引更新完成，没有文件变动"
        }
        snackbarHostState.showSnackbar(msg)
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
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (inSelectMode) {
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

                    // 日常增量更新横幅："更新索引中…"
                    if (repoState.phase == ScanPhase.UPDATING) {
                        IndexUpdatingBanner(scanned = repoState.scanned)
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

                // 底部筛选圆钮（目录名页不显示，还原原版）
                if (pagerState.currentPage != 2 && repoState.phase != ScanPhase.FIRST_BUILD) {
                    FilterRow(
                        page = pagerState.currentPage,
                        current = if (pagerState.currentPage == 0) nameFilter else officeFilter,
                        onSelect = { c ->
                            if (pagerState.currentPage == 0) nameFilter = c else officeFilter = c
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 20.dp),
                        sortByName = settings.sortByName,
                        onToggleSort = {
                            viewModel.updateSettings { s -> s.copy(sortByName = !s.sortByName) }
                        },
                    )
                }

                // 首次建索引全屏遮罩（原版 CreateIndexWidget 文案）
                if (repoState.phase == ScanPhase.FIRST_BUILD) {
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

/** 底部筛选圆钮行（还原原版：类别钮 + ∞ 所有 + 漏斗排序）。 */
@Composable
private fun FilterRow(
    page: Int,
    current: FileCategory,
    onSelect: (FileCategory) -> Unit,
    modifier: Modifier = Modifier,
    sortByName: Boolean,
    onToggleSort: () -> Unit,
) {
    val options = if (page == 0) {
        listOf(FileCategory.VIDEO, FileCategory.AUDIO, FileCategory.IMAGE, FileCategory.DOC)
    } else {
        listOf(FileCategory.WORD, FileCategory.EXCEL, FileCategory.PPT)
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End),
        verticalAlignment = Alignment.Bottom,
    ) {
        options.forEach { opt ->
            FilterButton(
                label = opt.label,
                icon = opt.icon,
                selected = current == opt,
                onClick = { onSelect(if (current == opt) FileCategory.ALL else opt) },
            )
        }
        Spacer(Modifier.width(4.dp))
        FilterButton(
            label = "所有",
            icon = Icons.Filled.AllInclusive,
            selected = current.isAll,
            onClick = { onSelect(FileCategory.ALL) },
        )
        FilterButton(
            label = if (sortByName) "按名称" else "按时间",
            icon = Icons.Filled.FilterList,
            selected = false,
            onClick = onToggleSort,
        )
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
                Icon(Icons.Filled.Menu, "菜单")
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, modifier = Modifier.size(22.dp))
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
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "取消选择") }
        },
        title = { Text("已选择 $count 项") },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Filled.DoneAll, "全选") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "删除") }
        },
    )
}

/** 抽屉（还原原版：标题 + 设置/欢迎页/关于 + 底部署名）。 */
@Composable
private fun AppDrawer(
    onSettings: () -> Unit,
    onWelcome: (String) -> Unit,
    onAbout: () -> Unit,
) {
    ModalDrawerSheet {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Anything", style = MaterialTheme.typography.headlineSmall)
            Text(
                "search everything on Android",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Spacer(Modifier.height(8.dp))
            NavigationDrawerItem(
                icon = { Icon(Icons.Filled.Settings, null) },
                label = { Text("设置") },
                selected = false,
                onClick = onSettings,
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Filled.Info, null) },
                label = { Text("欢迎页") },
                selected = false,
                onClick = { onWelcome(ROUTE_WELCOME) },
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Filled.Info, null) },
                label = { Text("关于") },
                selected = false,
                onClick = onAbout,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Made with ❤ by AnythingMobile\nGo 引擎 + Kotlin 外壳 · 开源",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** 日常增量更新横幅："更新索引中… 已扫描 X 项"。 */
@Composable
private fun IndexUpdatingBanner(scanned: Long) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                if (scanned > 0) "更新索引中… 已扫描 $scanned 项" else "更新索引中…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
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
