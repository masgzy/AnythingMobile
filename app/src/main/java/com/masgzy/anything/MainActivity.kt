package com.masgzy.anything

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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masgzy.anything.ui.AboutScreen
import com.masgzy.anything.ui.ROUTE_ABOUT
import com.masgzy.anything.ui.ROUTE_SETTINGS
import com.masgzy.anything.ui.ROUTE_WELCOME
import com.masgzy.anything.ui.SearchScreen
import com.masgzy.anything.ui.SettingsScreen
import com.masgzy.anything.ui.WelcomeScreen
import com.masgzy.anything.ui.theme.AnythingTheme
import kotlinx.coroutines.launch

/**
 * 应用入口：主题（外观设置实时生效）+ 带过渡动画的路由
 * （主界面 / 设置 / 关于 / 首次引导页）。
 *
 * 导航行为：
 *  - 设置/关于从侧边栏进入；返回（左上角箭头或系统返回键）
 *    回到主界面并重新打开侧边栏，而不是退出应用；
 *  - 侧边栏状态提升至此，跨路由保持，返回时可直接重开。
 *
 * 自动索引更新：SearchScreen 内部在 ON_RESUME 调用
 * AppViewModel.autoScanIfNeed()；引擎就绪时 ViewModel 也会补发一次
 * （索引已持久化，增量扫描通常一闪而过）。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels {
        AppViewModel.factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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
        AppNav(viewModel, onRequestAllFiles)
    }
}

/** 各路由的层级深度：主界面 0，设置/关于 1，引导页 2。决定转场滑动方向。 */
private fun routeDepth(route: String): Int = when (route) {
    "main" -> 0
    ROUTE_WELCOME -> 2
    else -> 1
}

@Composable
private fun AppNav(
    viewModel: AppViewModel,
    onRequestAllFiles: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var route by remember {
        mutableStateOf(if (settings.welcomeSeen) "main" else ROUTE_WELCOME)
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 设置/关于返回：回到主界面并重新打开侧边栏（用户从此处进入）。
    // 抽屉用 snapTo 瞬时打开而非动画展开：与路由转场同时进行的两段动画
    // 会在中端机上掉帧（实测明显卡顿），瞬时展开则只余一段转场，观感干脆。
    val backToDrawer: () -> Unit = {
        route = "main"
        scope.launch { drawerState.snapTo(DrawerValue.Open) }
    }

    // 拦截系统返回键，与左上角返回箭头行为一致
    BackHandler(enabled = route == ROUTE_SETTINGS || route == ROUTE_ABOUT) {
        backToDrawer()
    }

    AnimatedContent(
        targetState = route,
        transitionSpec = {
            // M3 fade-through 转场：旧页 60~90ms 快速淡出，新页延迟后
            // 淡入并从 92% 轻微放大。相比旧的全宽滑动，缩放是纯
            // graphicsLayer 变换、不触发重新测量，两块重组件同屏时的
            // 开销大幅降低 —— 这是从设置返回主界面卡顿的根因。
            val goingDeeper = routeDepth(targetState) > routeDepth(initialState)
            if (goingDeeper) {
                fadeIn(
                    tween(210, delayMillis = 70, easing = LinearOutSlowInEasing),
                ) + scaleIn(
                    initialScale = 0.92f,
                    animationSpec = tween(280, delayMillis = 70, easing = FastOutSlowInEasing),
                ) togetherWith fadeOut(tween(70, easing = FastOutSlowInEasing))
            } else {
                fadeIn(tween(220, easing = LinearOutSlowInEasing)) togetherWith
                    fadeOut(tween(90, easing = FastOutSlowInEasing))
            }
        },
        label = "nav",
    ) { r ->
        when (r) {
            ROUTE_WELCOME -> WelcomeScreen(
                onFinish = {
                    viewModel.markWelcomeSeen()
                    route = "main"
                },
            )
            ROUTE_SETTINGS -> SettingsScreen(
                viewModel = viewModel,
                onBack = backToDrawer,
                onShowWelcome = { route = ROUTE_WELCOME },
            )
            ROUTE_ABOUT -> AboutScreen(onBack = backToDrawer)
            else -> SearchScreen(
                viewModel = viewModel,
                onRequestAllFiles = onRequestAllFiles,
                onNavigate = { route = it },
                drawerState = drawerState,
            )
        }
    }
}
