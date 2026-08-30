package com.masgzy.anything

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.masgzy.anything.data.EngineRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * 搜索界面状态持有者。组合引擎仓库与权限逻辑，
 * Compose 层只与 ViewModel 交互。
 *
 * 鲁棒性：
 *  - 搜索输入经 150ms 防抖后再触达引擎，避免高频 JNI 调用；
 *  - 扫描启动等协程调用均由 EngineRepository 内部兜底，异常不外泄。
 *
 * 构造使用 AndroidViewModelFactory.getInstance(application)，
 * 见 MainActivity。
 */
@OptIn(FlowPreview::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {

    val repo = EngineRepository(application)

    /** 搜索词流：防抖后统一进入引擎查询。 */
    private val queryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            queryFlow.debounce(150).collect { repo.search(it) }
        }
    }

    fun startScan() {
        viewModelScope.launch {
            runCatching {
                repo.startScan(StoragePermissions.scanRoots(getApplication()))
            }
        }
    }

    fun cancelScan() = repo.cancelScan()

    fun search(query: String) {
        queryFlow.value = query
    }

    fun openFile(path: String) = repo.openFile(path)
}
