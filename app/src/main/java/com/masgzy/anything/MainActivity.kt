package com.masgzy.anything

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masgzy.anything.crash.CrashReporter
import com.masgzy.anything.log.AppLogger
import com.masgzy.anything.ui.DirectoryScreen
import com.masgzy.anything.ui.FloatingBottomBar
import com.masgzy.anything.ui.LocalFloatingBarPadding
import com.masgzy.anything.ui.SearchScreen
import com.masgzy.anything.ui.SettingsScreen
import com.masgzy.anything.ui.SyntaxHelpScreen
import com.masgzy.anything.ui.UpdateSheet
import com.masgzy.anything.ui.WelcomeScreen
import com.masgzy.anything.ui.log.LogScreen
import com.masgzy.anything.ui.theme.AnythingTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * 应用入口：主题（外观设置实时生效）+ 底栏三 tab（搜索/目录/设置）+ 首次引导页。
 *
 * 导航行为（alpha19）：三页 tab 由 HorizontalPager 承载（NPatch MainScreen 同款）——
 * 点击底栏平滑滚动切页，也支持手势横向滑动；子页面（语法教程）为栈式过渡
 * （右侧滑入 / 反向滑出），打开时底栏随之下沉隐藏，返回键语义与栈一致；
 * 引导页仅在首启出现，完成后进入搜索 tab。
 *
 * 液态玻璃（NPatch FloatingGlassBottomBar 同款）：设置开启且 Android 13+ 时，
 * 内容层经 layerBackdrop 逐帧录制，底栏 LiquidGlass 模式采样折射；
 * 内容延伸到底栏之下（玻璃取样需要），滚动列表尾部经
 * [LocalFloatingBarPadding] 实测预留（照 NPatch 动态测量方案）。
 *
 * 自动索引更新：SearchScreen 内部在 ON_RESUME 调用 AppViewModel.autoScanIfNeed()。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels {
        AppViewModel.factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 全局崩溃处理器必须最先安装（ImageToolbox GlobalExceptionHandler 同款模式）
        CrashReporter.install(applicationContext)
        super.onCreate(savedInstanceState)
        AppLogger.i("AnythingApp", "activity create, savedInstanceState=${savedInstanceState != null}")
        enableEdgeToEdge()
        setContent {
            Root(
                viewModel = viewModel,
                onRequestAllFiles = { StoragePermissions.requestAllFiles(this@MainActivity) },
            )
        }
    }
}

@Composable
private fun Root(
    viewModel: AppViewModel,
    onRequestAllFiles: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    AnythingTheme(settings = settings) {
        // 首启引导后进入主壳；设置页「重看引导」可回跳。
        // 引导 ⇄ 主壳为栈式过渡（引导从下方推入，完成时向上收回），
        // 替换原先 if/else 硬切的生硬跳变。
        var showWelcome by rememberSaveable { mutableStateOf(!settings.welcomeSeen) }
        AnimatedContent(
            targetState = showWelcome,
            transitionSpec = {
                if (targetState) {
                    // push：引导页自下而上滑入
                    (slideInVertically(tween(320)) { it } + fadeIn(tween(220))) togetherWith
                        fadeOut(tween(160))
                } else {
                    // pop：引导页向上滑出，主壳淡入
                    fadeIn(tween(220, delayMillis = 60)) togetherWith
                        (slideOutVertically(tween(300)) { -it } + fadeOut(tween(160)))
                }
            },
            label = "welcome",
        ) { welcome ->
            if (welcome) {
                WelcomeScreen(onFinish = {
                    viewModel.markWelcomeSeen()
                    showWelcome = false
                })
            } else {
                MainShell(viewModel, onRequestAllFiles, onShowWelcome = { showWelcome = true })
            }
        }
    }
    // 上一次崩溃未分享的报告：启动时提示用户分享（崩溃页拉起失败时的兜底入口）
    val activityContext = LocalContext.current as? android.app.Activity
    var pendingCrash by remember {
        mutableStateOf(
            activityContext != null && CrashReporter.hasPendingReport(activityContext),
        )
    }
    if (pendingCrash && activityContext != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("检测到上次崩溃") },
            text = { Text("上次运行发生异常，是否分享崩溃日志帮助修复？") },
            confirmButton = {
                TextButton(onClick = {
                    CrashReporter.shareReport(activityContext)
                    CrashReporter.clear(activityContext)
                    pendingCrash = false
                }) { Text("分享日志") }
            },
            dismissButton = {
                TextButton(onClick = {
                    CrashReporter.clear(activityContext)
                    pendingCrash = false
                }) { Text("忽略") }
            },
        )
    }
}

/**
 * 主壳：三页 HorizontalPager + 悬浮底栏 + 液态玻璃 backdrop 录制。
 *
 * 层次（与 NPatch MainScreen 同构）：
 *   Box(表面底色)
 *     ├─ HorizontalPager(layerBackdrop(backdrop)) —— 玻璃取样源，含底色打底
 *     ├─ 栈式子页面层（语法教程；出现时盖住 Pager，底栏同步隐藏）
 *     └─ FloatingBottomBar(悬浮时叠内容之上；通栏时在内容流内)
 */
@Composable
private fun MainShell(
    viewModel: AppViewModel,
    onRequestAllFiles: () -> Unit,
    onShowWelcome: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    var showSyntaxHelp by rememberSaveable { mutableStateOf(false) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    // 启动自动检查更新（alpha24）：首次挂载后延迟几秒，开关关闭则不查
    LaunchedEffect(Unit) { viewModel.maybeAutoCheckOnStartup() }

    // 底栏选中态跟随 settledPage（手势滑动时指示器同步，NPatch 同款）
    val settledPage by remember(pagerState) {
        derivedStateOf { pagerState.settledPage }
    }

    // 液态玻璃门控：与 NPatch 一致，AGSL 折射 shader 需要 Android 13（Tiramisu）
    val glass = settings.liquidGlass &&
        settings.showPaginator &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    // 页面层录制（玻璃取样源）：surface 打底避免透明区域穿透到窗口
    val surface = MaterialTheme.colorScheme.surface
    val backdrop = if (glass) {
        rememberLayerBackdrop { drawRect(surface); drawContent() }
    } else {
        null
    }

    // —— 底栏占位实测（NPatch dynamicFloatingBottomBarPadding 同款）——
    val density = LocalDensity.current
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var barPadding by remember { mutableStateOf(88.dp + navInset) }
    val onBarHeight: (androidx.compose.ui.unit.Dp) -> Unit = { barPadding = it }

    // 返回语义：子页面优先关闭；否则目录/设置 tab 平滑滚回搜索（与底栏语义一致）
    BackHandler(enabled = settledPage != 0 && !showSyntaxHelp && !showLog) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }
    BackHandler(enabled = showSyntaxHelp) { showSyntaxHelp = false }
    BackHandler(enabled = showLog) { showLog = false }

    // 底栏占位 padding 对整个壳生效（页面滚动尾部 contentPadding 消费它）
    CompositionLocalProvider(LocalFloatingBarPadding provides barPadding) {
        Box(
            Modifier
                .fillMaxSize()
                .background(surface),
        ) {
        // —— 内容层（玻璃 backdrop 的录制源；教程页打开时底栏隐藏，Pager 仍在栈底）——
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier,
                ),
        ) { page ->
            when (page) {
                1 -> DirectoryScreen(viewModel)
                2 -> SettingsScreen(
                    viewModel = viewModel,
                    onShowWelcome = onShowWelcome,
                    onOpenSyntaxHelp = { showSyntaxHelp = true },
                    onOpenLog = { showLog = true },
                )
                else -> SearchScreen(
                    viewModel = viewModel,
                    onRequestAllFiles = onRequestAllFiles,
                    onOpenSyntaxHelp = { showSyntaxHelp = true },
                )
            }
        }

        // —— 栈式子页面：语法教程 / 调试日志（右侧滑入 / 反向滑出；盖住 Pager，底栏隐藏）——
        AnimatedContent(
            targetState = showSyntaxHelp,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally(tween(300)) { it } + fadeIn(tween(200))) togetherWith
                        fadeOut(tween(150))
                } else {
                    fadeIn(tween(180, delayMillis = 40)) togetherWith
                        (slideOutHorizontally(tween(280)) { it } + fadeOut(tween(180)))
                }
            },
            label = "syntaxHelp",
        ) { help ->
            if (help) {
                SyntaxHelpScreen(onBack = { showSyntaxHelp = false })
            } else {
                // 占位：不渲染任何内容（Pager 透出）
                Box(Modifier.fillMaxSize())
            }
        }

        // —— 栈式子页面：调试日志（同款转场）——
        AnimatedContent(
            targetState = showLog,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally(tween(300)) { it } + fadeIn(tween(200))) togetherWith
                        fadeOut(tween(150))
                } else {
                    fadeIn(tween(180, delayMillis = 40)) togetherWith
                        (slideOutHorizontally(tween(280)) { it } + fadeOut(tween(180)))
                }
            },
            label = "logScreen",
        ) { log ->
            if (log) {
                LogScreen(onBack = { showLog = false })
            } else {
                Box(Modifier.fillMaxSize())
            }
        }

        // —— 底栏（任一栈页打开时下沉隐藏，NPatch 栈页同款语义）——
        androidx.compose.animation.AnimatedVisibility(
            visible = !showSyntaxHelp && !showLog,
            enter = fadeIn(tween(200)) + slideInVertically(tween(260)) { it / 2 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            FloatingBottomBar(
                selected = pagerState.currentPage,
                onSelect = { index ->
                    scope.launch { pagerState.animateScrollToPage(index) }
                },
                floating = settings.showPaginator,
                glass = glass,
                backdrop = backdrop,
                onBarHeight = onBarHeight,
            )
        }
        }
    }

    // —— 更新弹窗（窗口层浮层，挂载位置不影响呈现；任意 tab 均可弹出）——
    if (updateState is AppViewModel.UpdateState.Available) {
        UpdateSheet(
            release = (updateState as AppViewModel.UpdateState.Available).release,
            onDismiss = viewModel::dismissUpdate,
        )
    }
}
