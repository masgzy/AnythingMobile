package com.masgzy.anything

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.masgzy.anything.data.AppSettings
import com.masgzy.anything.data.EngineRepository
import com.masgzy.anything.data.SettingsRepository
import com.masgzy.anything.data.ThemeMode
import com.masgzy.anything.data.UiHit
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.File

/**
 * 应用级状态持有者：设置、搜索、索引更新、多选与删除。
 *
 * 鲁棒性：
 *  - 搜索输入经 150ms 防抖后再触达引擎，避免高频 JNI 调用；
 *  - 自动扫描带 800ms 防抖（快速前后台切换不重复触发），
 *    且引擎就绪翻转时立即补发一次（覆盖"就绪晚于首个 ON_RESUME"的窗口）；
 *  - 所有引擎调用由 EngineRepository 内部兜底，异常不外泄。
 *
 * 扫描速度（v0.3）：索引随快照磁盘持久化，进入应用时恢复即搜，
 * 自动增量扫描只处理变动文件，通常一闪而过。
 */
@OptIn(FlowPreview::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {

    val repo = EngineRepository(application)
    private val settingsRepo = SettingsRepository.get(application)

    private val _settings = MutableStateFlow(settingsRepo.load())
    val settings: MutableStateFlow<AppSettings> = _settings

    /** 搜索词流：防抖后统一进入引擎查询。 */
    private val queryFlow = MutableStateFlow("")

    /** 自动扫描防抖时间戳（毫秒）。 */
    private var lastAutoScanAt = 0L

    init {
        viewModelScope.launch {
            queryFlow.debounce(150).collect { repo.search(it) }
        }
        // 引擎从后台线程就绪时，主动补一次自动扫描：
        // 冷启动时首个 ON_RESUME 往往早于引擎就绪，若不补发，
        // 本次进入应用的自动索引更新会被跳过。
        viewModelScope.launch {
            var wasReady = false
            repo.state.collect { st ->
                if (st.ready && !wasReady) autoScanIfNeed()
                wasReady = st.ready
            }
        }
    }

    // ---- 设置 ----

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        settingsRepo.run {
            setThemeMode(next.themeMode)
            setDynamicColor(next.dynamicColor)
            setRoleColors(next.roleColors)
            setPaletteStyle(next.paletteStyle)
            setThemeContrast(next.themeContrast)
            setInvertColors(next.invertColors)
            setAmoled(next.amoled)
            setAutoScanOnEnter(next.autoScanOnEnter)
            setSortByName(next.sortByName)
        }
    }

    fun markWelcomeSeen() {
        settingsRepo.setWelcomeSeen(true)
        _settings.value = _settings.value.copy(welcomeSeen = true)
    }

    // ---- 索引更新 ----

    /**
     * 进入应用（ON_RESUME / 引擎就绪）自动增量扫描：
     * 需要引擎可用 + 已授权 + 未在扫描中 + 用户未关闭 + 距上次触发超过 800ms。
     */
    fun autoScanIfNeed() {
        val context = getApplication<Application>()
        if (!_settings.value.autoScanOnEnter) return
        if (!repo.hasEngine) return
        if (!StoragePermissions.hasStorageAccess(context)) return
        if (repo.state.value.phase != com.masgzy.anything.data.ScanPhase.IDLE) return
        val now = System.currentTimeMillis()
        if (now - lastAutoScanAt < 800) return
        lastAutoScanAt = now
        startScan(incremental = true)
    }

    fun startScan(incremental: Boolean) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                repo.startScan(StoragePermissions.scanRoots(context), incremental)
            }
        }
    }

    fun cancelScan() = repo.cancelScan()

    /** 在系统设置里打开本应用详情（兜底授权入口）。 */
    fun openAppDetails() {
        val context = getApplication<Application>()
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ---- 搜索 ----

    fun search(query: String) {
        queryFlow.value = query
    }

    // ---- 结果操作 ----

    fun openFile(path: String) = repo.openFile(path)

    fun shareFile(path: String) = repo.shareFile(path)

    /** 删除文件/空目录并同步索引；返回成功数。 */
    fun deleteItems(paths: List<String>): Int {
        var deleted = 0
        for (p in paths) {
            if (runCatching { File(p).delete() }.getOrDefault(false)) deleted++
        }
        if (deleted > 0) {
            repo.removePaths(paths)
            // 删除后重查一次，刷新当前结果
            search(queryFlow.value)
        }
        return deleted
    }

    /** 打开目录（系统文件管理器/文档 UI）。失败返回 false 由 UI 提示。 */
    fun openFolder(path: String): Boolean {
        val context = getApplication<Application>()
        return runCatching {
            // DocumentsUI 支持以 vnd.android.document/directory 类型打开目录
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(path), "vnd.android.document/directory")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrElse { false }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }
}
