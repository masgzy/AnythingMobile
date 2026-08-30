package com.masgzy.anything

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masgzy.anything.ui.AboutScreen
import com.masgzy.anything.ui.ROUTE_ABOUT
import com.masgzy.anything.ui.ROUTE_SETTINGS
import com.masgzy.anything.ui.ROUTE_WELCOME
import com.masgzy.anything.ui.SearchScreen
import com.masgzy.anything.ui.SettingsScreen
import com.masgzy.anything.ui.WelcomeScreen
import com.masgzy.anything.ui.theme.AnythingTheme

/**
 * 应用入口：主题（外观设置实时生效）+ 简单路由
 * （主界面 / 设置 / 关于 / 首次引导页）。
 *
 * 自动索引更新：SearchScreen 内部在 ON_RESUME 调用
 * AppViewModel.autoScanIfNeed()（增量、有变动才重解析）。
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

@Composable
private fun AppNav(
    viewModel: AppViewModel,
    onRequestAllFiles: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var route by remember {
        mutableStateOf(if (settings.welcomeSeen) "main" else ROUTE_WELCOME)
    }

    when (route) {
        ROUTE_WELCOME -> WelcomeScreen(
            onFinish = {
                viewModel.markWelcomeSeen()
                route = "main"
            },
        )
        ROUTE_SETTINGS -> SettingsScreen(
            viewModel = viewModel,
            onBack = { route = "main" },
            onShowWelcome = { route = ROUTE_WELCOME },
        )
        ROUTE_ABOUT -> AboutScreen(onBack = { route = "main" })
        else -> SearchScreen(
            viewModel = viewModel,
            onRequestAllFiles = onRequestAllFiles,
            onNavigate = { route = it },
        )
    }
}
