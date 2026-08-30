package com.masgzy.anything

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.masgzy.anything.data.EngineRepository
import kotlinx.coroutines.launch

/**
 * 搜索界面状态持有者。组合引擎仓库与权限逻辑，
 * Compose 层只与 ViewModel 交互。
 *
 * 构造使用 AndroidViewModelFactory.getInstance(application)，
 * 见 MainActivity。
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    val repo = EngineRepository(application)

    fun startScan() {
        viewModelScope.launch {
            repo.startScan(StoragePermissions.scanRoots(getApplication()))
        }
    }

    fun cancelScan() = repo.cancelScan()

    fun search(query: String) = repo.search(query)

    fun openFile(path: String) = repo.openFile(path)
}
