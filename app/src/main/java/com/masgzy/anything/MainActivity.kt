package com.masgzy.anything

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.masgzy.anything.ui.SearchScreen
import com.masgzy.anything.ui.theme.AnythingTheme

/**
 * 主界面：搜索框 + 结果列表 + 扫描控制。
 * 引擎初始化/权限引导/扫描进度全部由 AppViewModel 驱动。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels { AppViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnythingTheme {
                SearchScreen(
                    viewModel = viewModel,
                    onRequestAllFiles = { StoragePermissions.requestAllFiles(this) }
                )
            }
        }
    }
}
