package com.masgzy.anything

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masgzy.anything.crash.CrashReporter
import com.masgzy.anything.ui.DirectoryScreen
import com.masgzy.anything.ui.FloatingBottomBar
import com.masgzy.anything.ui.SearchScreen
import com.masgzy.anything.ui.SettingsScreen
import com.masgzy.anything.ui.SyntaxHelpScreen
import com.masgzy.anything.ui.WelcomeScreen
import com.masgzy.anything.ui.theme.AnythingTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * 应用入口：主题（外观设置实时生效）+ 底栏三 tab（搜索/目录/设置）+ 首次引导页。
 *
 * 导航行为（alpha18）：三页 tab（搜索/目录/设置）+ 悬浮分页底栏（默认开，可改通栏）；
 * 旧侧边栏抽屉已移除——搜索语法教程入口在搜索顶栏 ? 与设置页「其他」分区，关于并入设置页；
 * 引导页仅在首启出现，完成后进入搜索 tab。
 *
 * 液态玻璃（NPatch FloatingGlassBottomBar 同款）：设置开启且 Android 13+ 时，
 * 内容层经 layerBackdrop 逐帧录制，底栏 LiquidGlass 模式采样折射；
 * 内容延伸到底栏之下（玻璃取样需要），滚动列表尾部用 contentPadding 预留。
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
        // 首启引导后进入主壳；设置页「重看引导」可回跳
        var showWelcome by rememberSaveable { mutableStateOf(!settings.welcomeSeen) }
        if (showWelcome) {
            WelcomeScreen(onFinish = {
                viewModel.markWelcomeSeen()
                showWelcome = false
            })
        } else {
            MainShell(viewModel, onRequestAllFiles, onShowWelcome = { showWelcome = true })
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
 * 主壳：三页 tab + 分页底栏 + 液态玻璃 backdrop 录制。
 *
 * 层次（与 NPatch MainScreen 同构）：
 *   Box(表面底色)
 *     ├─ 内容层：layerBackdrop(backdrop) 录制（玻璃取样源，含底色打底）
 *     └─ FloatingBottomBar(悬浮时叠在内容之上；通栏时在内容流内)
 */
@Composable
private fun MainShell(
    viewModel: AppViewModel,
    onRequestAllFiles: () -> Unit,
    onShowWelcome: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSyntaxHelp by rememberSaveable { mutableStateOf(false) }

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

    // 返回语义：语法教程页优先关闭；否则目录/设置 tab 返回搜索 tab（与底栏语义一致）
    BackHandler(enabled = tab != 0) { tab = 0 }
    BackHandler(enabled = showSyntaxHelp) { showSyntaxHelp = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(surface),
    ) {
        // —— 内容层（玻璃 backdrop 的录制源）——
        Box(
            Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            if (showSyntaxHelp) {
                SyntaxHelpScreen(onBack = { showSyntaxHelp = false })
            } else AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    // M3 fade-through：旧页 70ms 快速淡出，新页延迟后淡入并轻微放大
                    fadeIn(
                        tween(210, delayMillis = 70, easing = LinearOutSlowInEasing),
                    ) + scaleIn(
                        initialScale = 0.96f,
                        animationSpec = tween(280, delayMillis = 70, easing = FastOutSlowInEasing),
                    ) togetherWith fadeOut(tween(70, easing = FastOutSlowInEasing))
                },
                label = "tab",
            ) { t ->
                when (t) {
                    1 -> DirectoryScreen(viewModel)
                    2 -> SettingsScreen(
                        viewModel = viewModel,
                        onBack = { tab = 0 },
                        onShowWelcome = onShowWelcome,
                        onOpenSyntaxHelp = { showSyntaxHelp = true },
                    )
                    else -> SearchScreen(
                        viewModel = viewModel,
                        onRequestAllFiles = onRequestAllFiles,
                        onOpenSyntaxHelp = { showSyntaxHelp = true },
                    )
                }
            }
        }

        // —— 分页底栏（悬浮时叠内容之上，玻璃实时取样内容层）——
        FloatingBottomBar(
            selected = tab,
            onSelect = { tab = it },
            floating = settings.showPaginator,
            glass = glass,
            backdrop = backdrop,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
