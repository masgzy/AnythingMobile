package com.masgzy.anything

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.masgzy.anything.BuildConfig
import com.masgzy.anything.data.AppSettings
import com.masgzy.anything.data.EngineRepository
import com.masgzy.anything.data.DesignStyle
import com.masgzy.anything.data.IndexScope
import com.masgzy.anything.data.IndexStats
import com.masgzy.anything.data.ScanPhase
import com.masgzy.anything.data.ScanSummary
import com.masgzy.anything.data.SafDirs
import com.masgzy.anything.data.SettingsRepository
import com.masgzy.anything.data.ShizukuAccess
import com.masgzy.anything.data.ThemeMode
import com.masgzy.anything.data.UiHit
import com.masgzy.anything.data.UpdateRepository
import com.masgzy.anything.log.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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

    /** Shizuku/Stellar 特权通道状态（设置页展示与授权入口）。 */
    val shizukuState: StateFlow<ShizukuAccess.State> = ShizukuAccess.state

    /** 扩展条目数（Android/data 已收录条目，设置页展示）。 */
    private val _externalCount = MutableStateFlow(0)
    val externalCount: StateFlow<Int> = _externalCount

    /** Shizuku 特权导出缓存占用字节；-1 表示尚未统计（设置页展示）。 */
    private val _privCacheBytes = MutableStateFlow(-1L)
    val privCacheBytes: StateFlow<Long> = _privCacheBytes

    /** 引擎统计快照（目录页索引状态卡展示）。 */
    private val _indexStats = MutableStateFlow<IndexStats?>(null)
    val indexStats: StateFlow<IndexStats?> = _indexStats

    /** 应用更新检查状态（设置页「版本」项 + 更新弹窗消费）。 */
    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data class Available(val release: UpdateRepository.ReleaseInfo) : UpdateState
        data object UpToDate : UpdateState
        data class Failed(val message: String) : UpdateState
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState

    /** 自动检查被用户关过弹窗后，本次会话不再自动弹（ImageToolbox cancelledUpdate 同语义）。 */
    private var autoCheckSuppressed = false

    /** 启动自动检查只触发一次。 */
    private var autoCheckStarted = false

    /** 自动扫描防抖时间戳（毫秒）。 */
    private var lastAutoScanAt = 0L

    /** Android/data 特权枚举进行中标记（防重入）。 */
    @Volatile
    private var refreshingAndroidData = false

    init {
        ShizukuAccess.init(application)
        // 日志等级：设置加载后立即生效（后续变更在 updateSettings 内同步）
        AppLogger.level = _settings.value.logLevel
        AppLogger.i(TAG, "app start, logLevel=${AppLogger.levelName(AppLogger.level)}")
        viewModelScope.launch {
            queryFlow.debounce(150).collect { repo.search(it) }
        }
        // 引擎从后台线程就绪时，主动补一次自动扫描：
        // 冷启动时首个 ON_RESUME 往往早于引擎就绪，若不补发，
        // 本次进入应用的自动索引更新会被跳过。
        // 扫描收尾后联动刷新扩展索引（Android/data 只在常规扫描后追加）。
        viewModelScope.launch {
            var wasReady = false
            var lastSummarySeen: ScanSummary? = null
            repo.state.collect { st ->
                if (st.ready && !wasReady) {
                    AppLogger.i(TAG, "engine ready -> auto scan")
                    autoScanIfNeed()
                    refreshExternalCount()
                    refreshIndexStats()
                }
                if (st.lastSummary != null && st.lastSummary !== lastSummarySeen) {
                    lastSummarySeen = st.lastSummary
                    AppLogger.d(TAG, "summary#${st.lastSummary!!.seq} -> refreshAndroidData")
                    refreshAndroidData()
                    refreshIndexStats()
                }
                wasReady = st.ready
            }
        }
        // Shizuku/Stellar 授权成功（含运行中授权弹窗返回）后立即刷新
        viewModelScope.launch {
            var wasGranted = false
            ShizukuAccess.state.collect { s ->
                val granted = s.status == ShizukuAccess.Status.GRANTED
                if (granted && !wasGranted) refreshAndroidData()
                wasGranted = granted
            }
        }
    }

    // ---- 设置 ----

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        if (AppLogger.level != next.logLevel) {
            AppLogger.level = next.logLevel
            AppLogger.i("AnythingVM", "log level changed to ${AppLogger.levelName(next.logLevel)}")
        }
        settingsRepo.run {
            setThemeMode(next.themeMode)
            setDesignStyle(next.designStyle)
            setDynamicColor(next.dynamicColor)
            setRoleColors(next.roleColors)
            setPaletteStyle(next.paletteStyle)
            setThemeContrast(next.themeContrast)
            setInvertColors(next.invertColors)
            setAmoled(next.amoled)
            setAutoScanOnEnter(next.autoScanOnEnter)
            setScanAndroidData(next.scanAndroidData)
            setFilterLabelSeconds(next.filterLabelSeconds)
            setSortByName(next.sortByName)
            setShowPaginator(next.showPaginator)
            setLiquidGlass(next.liquidGlass)
            setIndexScope(next.indexScope)
            setSearchDirs(next.searchDirs)
            setLogLevel(next.logLevel)
            setAutoCheckUpdates(next.autoCheckUpdates)
        }
    }

    fun markWelcomeSeen() {
        settingsRepo.setWelcomeSeen(true)
        _settings.value = _settings.value.copy(welcomeSeen = true)
    }

    /** 悬浮分页底栏（默认开）。 */
    fun setShowPaginator(v: Boolean) = updateSettings { s -> s.copy(showPaginator = v) }

    /** 设计风格：Material Design 3 / Miuix（HyperOS 风格，alpha28）。 */
    fun setDesignStyle(v: DesignStyle) = updateSettings { s -> s.copy(designStyle = v) }

    /** 液态玻璃底栏（悬浮形态 + Android 13+ 才完整生效）。 */
    fun setLiquidGlass(v: Boolean) = updateSettings { s -> s.copy(liquidGlass = v) }

    /** 索引/搜索范围：全盘+目录 / 仅目录 / 仅全盘（alpha26 三模式）。 */
    fun setIndexScope(v: IndexScope) = updateSettings { s -> s.copy(indexScope = v) }

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
        AppLogger.d(TAG, "autoScan triggered")
        startScan(incremental = true)
    }

    fun startScan(incremental: Boolean) {
        val context = getApplication<Application>()
        // IO 线程：startScan 内部的 isIndexEmpty() 会同步走 stats()（JNI + JSON），
        // engine.startScan 本身也涉及跨界调用，均不回占主线程
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val s = _settings.value
                when (s.indexScope) {
                    // 仅目录：全部可映射为物理路径时只扫这些根（复用常规
                    // 遍历/解析管线，增量语义不变）；存在不可映射目录时
                    // 索引由 SAF 注入通道负责（目录页手动同步），此处不动
                    IndexScope.DIRS_ONLY -> {
                        if (s.searchDirs.isNotEmpty()) {
                            val mapped = s.searchDirs.map { SafDirs.treeToPath(context, it) }
                            if (mapped.all { it != null }) {
                                repo.startScan(mapped.filterNotNull().toTypedArray(), incremental)
                                return@runCatching
                            }
                            if (!StoragePermissions.hasStorageAccess(context) ||
                                mapped.any { it == null }
                            ) {
                                return@runCatching // SAF 通道管理的索引，不按全盘重建覆盖
                            }
                        }
                        // 仅目录但目录清单为空：无可扫范围，不启动全盘兜底
                        repo.setStatusText("尚未授权任何目录，请先在「目录」页添加")
                    }
                    // 仅全盘 / 全盘+目录：常规扫描均按全盘根进行；差异在
                    // 「同步索引」是否额外 SAF 注入虚拟目录（见 syncDirIndex），
                    // 与 Android/data 特权条目的收录联动无关
                    IndexScope.FULL_DISK_ONLY, IndexScope.FULL_DISK_AND_DIRS ->
                        repo.startScan(StoragePermissions.scanRoots(context), incremental)
                }
            }
        }
    }

    fun cancelScan() = repo.cancelScan()

    // ---- 目录页（SAF 文件夹选择器授权）----

    /** 添加已授权目录（UI 回调里已 takePersistableUriPermission）。 */
    fun addSearchDir(uriStr: String) {
        updateSettings { s -> s.copy(searchDirs = (s.searchDirs + uriStr).distinct()) }
        settingsRepo.setSearchDirs(_settings.value.searchDirs)
    }

    /** 移除目录并释放持久化授权。 */
    fun removeSearchDir(uriStr: String) {
        SafDirs.release(getApplication(), uriStr)
        updateSettings { s -> s.copy(searchDirs = s.searchDirs - uriStr) }
        settingsRepo.setSearchDirs(_settings.value.searchDirs)
    }

    /**
     * 目录页"同步索引"，按索引范围三模式分流（alpha26）：
     *  - 仅全盘：等同常规全量重建（快）；
     *  - 仅目录：全部可映射且已授所有文件访问 → 常规扫描；否则 SAF 遍历 +
     *    Ingest 注入（content:// 的唯一通路）；
     *  - 全盘+目录：先全盘重建，再对目录清单中不可物理映射的目录
     *    （DocumentsProvider 虚拟目录，全盘扫描覆盖不到）逐个 SAF 注入；
     *    可映射目录已被全盘覆盖，跳过以免重复遍历。
     */
    fun syncDirIndex() {
        val context = getApplication<Application>()
        val s = _settings.value
        if (repo.state.value.phase != com.masgzy.anything.data.ScanPhase.IDLE) return
        when (s.indexScope) {
            IndexScope.FULL_DISK_ONLY -> {
                startScan(incremental = false)
                return
            }
            IndexScope.DIRS_ONLY -> if (s.searchDirs.isEmpty()) {
                repo.setStatusText("尚未授权任何目录")
                return
            }
            IndexScope.FULL_DISK_AND_DIRS -> if (s.searchDirs.isEmpty()) {
                startScan(incremental = false)
                return
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val mapped = s.searchDirs.map { SafDirs.treeToPath(context, it) }
            val fullDiskOk = StoragePermissions.hasStorageAccess(context)
            val allMapped = mapped.all { it != null }
            // 1) 常规管线段：可全盘则全盘重建；仅目录模式且全部可映射则只扫目录
            val scannedPhysical = if (s.indexScope == IndexScope.FULL_DISK_AND_DIRS && fullDiskOk) {
                repo.startScan(StoragePermissions.scanRoots(context), incremental = false)
                // 等本轮扫描结束再注入（引擎扫描互斥，Ingest 在扫描中会被拒绝）
                while (repo.state.value.phase != com.masgzy.anything.data.ScanPhase.IDLE) {
                    delay(200)
                }
                true
            } else if (allMapped) {
                val roots = mapped.filterNotNull().toTypedArray()
                if (roots.isNotEmpty()) {
                    repo.startScan(roots, incremental = false)
                    while (repo.state.value.phase != com.masgzy.anything.data.ScanPhase.IDLE) {
                        delay(200)
                    }
                }
                true
            } else {
                false
            }
            // 2) SAF 注入段：存在不可映射目录（或常规段未能覆盖）时逐个收集注入；
            //    全盘+目录模式下仅处理不可映射的虚拟目录
            val pendingIndices = s.searchDirs.indices.filter { i ->
                when {
                    s.indexScope == IndexScope.FULL_DISK_AND_DIRS -> mapped[i] == null && fullDiskOk
                    scannedPhysical -> false
                    else -> mapped[i] == null
                }
            }
            if (pendingIndices.isEmpty()) {
                if (!scannedPhysical && s.searchDirs.isEmpty()) {
                    repo.setStatusText("尚未授权任何目录")
                }
                return@launch
            }
            if (!scannedPhysical) repo.setStatusText("正在遍历目录…")
            val uris = pendingIndices.map { s.searchDirs[it] }
            val json = SafDirs.collectEntries(
                context,
                uris,
                extractDoc = { name, bytes -> repo.extractDoc(name, bytes) },
                onProgress = { n -> repo.setStatusText("已收录 $n 项…") },
            )
            if (json == null) {
                repo.setStatusText("目录授权已失效，请重新添加")
            } else if (!repo.ingest(json)) {
                repo.setStatusText("引擎忙，稍后再试")
            }
        }
    }

    // ---- Android/data 扩展扫描（Shizuku/Stellar 特权通道） ----

    fun refreshExternalCount() {
        // externalCount() 内部同步调 stats()（JNI + JSON 解析），放 IO 线程
        viewModelScope.launch(Dispatchers.IO) {
            _externalCount.value = repo.externalCount()
        }
    }

    /** 刷新索引统计快照（目录页状态卡）；进入目录页/扫描收尾时调用。 */
    fun refreshIndexStats() {
        viewModelScope.launch(Dispatchers.IO) {
            _indexStats.value = repo.indexStats()
        }
    }

    /** 统计特权导出缓存占用（进入设置页时调用）。 */
    fun refreshPrivCacheStats() {
        viewModelScope.launch(Dispatchers.IO) {
            _privCacheBytes.value = repo.privilegedCacheStats().second
        }
    }

    /** 清空特权导出缓存（打开 Android/data 文件时产生的临时副本）。 */
    fun clearPrivilegedCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = repo.clearPrivilegedCache()
            _privCacheBytes.value = if (ok) 0L else repo.privilegedCacheStats().second
            repo.setStatusText(if (ok) "已清理特权导出缓存" else "清理失败，稍后重试")
        }
    }

    /** 目录页「Android/data」条目开关（alpha26 自设置页迁入）：
     *  开启立即枚举；关闭清空扩展条目（随快照同步）。 */
    fun setAndroidDataEnabled(on: Boolean) {
        updateSettings { s -> s.copy(scanAndroidData = on) }
        if (on) {
            refreshAndroidData()
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                repo.replaceExternalEntries("", emptyList(), emptyList())
                refreshExternalCount()
            }
        }
    }

    /** 发起 Shizuku/Stellar 授权（设置页授权按钮）。 */
    fun requestShizukuPermission() {
        ShizukuAccess.requestPermission()
        // 弹窗关闭后监听器会刷新状态；这里再主动刷一次兜底
        ShizukuAccess.refresh()
    }

    /** Shizuku/Stellar 不可用时引导用户去官网下载（浏览器打开，不违背离线承诺）。 */
    fun openShizukuDownload() {
        val context = getApplication<Application>()
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 经 Shizuku/Stellar 枚举 /Android/data 并喂给引擎扩展索引。
     * 前置：开关开启 + 已授权 + 引擎空闲；不满足则静默跳过。
     * 引擎扫描中时引擎会拒绝替换 —— 因此枚举固定在扫描收尾后触发。
     */
    fun refreshAndroidData() {
        if (!_settings.value.scanAndroidData) return
        if (ShizukuAccess.state.value.status != ShizukuAccess.Status.GRANTED) return
        if (!repo.hasEngine) return
        if (repo.state.value.phase != ScanPhase.IDLE) return
        if (refreshingAndroidData) return
        refreshingAndroidData = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val base = Environment.getExternalStorageDirectory()?.absolutePath
                    ?: return@launch
                val root = "$base/Android/data"
                AppLogger.i(TAG, "refreshAndroidData: enumerating via shizuku root=$root")
                repo.setStatusText("正在通过 Shizuku 读取 Android/data…")
                val dirs = ShizukuAccess.listTree(root, "d")
                val files = ShizukuAccess.listTree(root, "f")
                if (dirs == null || files == null) {
                    AppLogger.w(TAG, "refreshAndroidData: listTree failed (shizuku not running/denied)")
                    repo.setStatusText("Android/data 读取失败：请确认 Shizuku/Stellar 正在运行且已授权")
                    return@launch
                }
                val ok = repo.replaceExternalEntries(root, dirs, files)
                AppLogger.i(
                    TAG,
                    "refreshAndroidData: replace ok=$ok dirs=${dirs.size} files=${files.size}"
                )
                if (ok) {
                    refreshExternalCount()
                    repo.setStatusText("已收录 Android/data ${dirs.size + files.size} 项")
                } else {
                    repo.setStatusText("扩展索引更新失败（引擎忙或不可用，稍后自动重试）")
                }
            } finally {
                refreshingAndroidData = false
            }
        }
    }

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

    /** 打开文件；Android/data 等特权路径先经 Shizuku 导出缓存副本。 */
    fun openFile(path: String) {
        viewModelScope.launch { repo.openFile(path) }
    }

    /** 分享文件；特权路径同上。 */
    fun shareFile(path: String) {
        viewModelScope.launch { repo.shareFile(path) }
    }

    /**
     * 删除文件/目录（递归）并同步索引；IO 线程执行。
     * 目录会连同全部后代一起删除（walkBottomUp 自底向上），
     * 且所有成功删除的路径（含子文件）都会从索引同步移除，
     * 避免删目录后子文件残留在结果里。完成后刷新当前搜索结果。
     */
    fun deleteItems(paths: List<String>) {
        if (paths.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val removedFromIndex = mutableListOf<String>()
            var failed = 0
            for (p in paths) {
                val f = File(p)
                // 自底向上枚举（目录自身在最后）；失败时依然尽力删除其余条目
                val targets = runCatching {
                    if (f.isDirectory) f.walkBottomUp().toList() else listOf(f)
                }.getOrDefault(emptyList())
                if (targets.isEmpty()) {
                    failed++
                    continue
                }
                var allDeleted = true
                for (t in targets) {
                    if (runCatching { t.delete() }.getOrDefault(false)) {
                        removedFromIndex.add(t.absolutePath)
                    } else {
                        allDeleted = false
                    }
                }
                if (!allDeleted) failed++
            }
            if (removedFromIndex.isNotEmpty()) {
                repo.removePaths(removedFromIndex)
                // 刷新当前结果。
                // 注意：不能走 queryFlow —— StateFlow 对相同值不重发，
                // collect 永不触发（这正是旧版"删除后不刷新、重新搜一遍才
                // 消失"的根因），改为直接调用搜索。
                runCatching { repo.search(queryFlow.value) }
            }
            if (failed > 0) {
                repo.setStatusText("有 $failed 项删除失败（可能被占用或只读）")
            }
        }
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

    // ---- 检查更新（GitHub Releases API，alpha24）----

    /**
     * 启动自动检查：MainShell 首次挂载后调用，延迟几秒避开冷启动繁忙期；
     * 开关关闭或本会话已被抑制（用户关过弹窗）则不查。无更新/失败静默回 Idle。
     * offline 渠道（BuildConfig.UPDATE_CHECK=false）直接短路：
     * 该渠道同时已在 Manifest 层移除 INTERNET 权限。
     */
    fun maybeAutoCheckOnStartup() {
        if (!BuildConfig.UPDATE_CHECK) return
        if (autoCheckStarted) return
        autoCheckStarted = true
        if (!_settings.value.autoCheckUpdates) return
        viewModelScope.launch {
            delay(4_000)
            if (!autoCheckSuppressed) checkForUpdates(manual = false)
        }
    }

    /**
     * 检查更新（设置页「版本」项点击 = manual；自动 = silent）。
     * 手动检查结果全量反馈到 [updateState]（含失败提示）；
     * 自动检查仅在有新版本时置 Available，无更新/失败静默回 Idle 不打扰。
     * offline 渠道短路为 UpToDate（UI 层已隐藏入口，防御性兜底）。
     */
    fun checkForUpdates(manual: Boolean) {
        if (!BuildConfig.UPDATE_CHECK) {
            _updateState.value = UpdateState.UpToDate
            return
        }
        if (_updateState.value is UpdateState.Checking) return
        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            AppLogger.i(TAG, "update check, manual=$manual")
            val result = runCatching { UpdateRepository.fetchLatestRelease() }
            val release = result.getOrNull()
            when {
                release != null && UpdateRepository.isNewer(release.tagName) -> {
                    // 有新版本：手动检查也应弹出（清除会话抑制）
                    autoCheckSuppressed = false
                    AppLogger.i(TAG, "update available, tag=${release.tagName}")
                    _updateState.value = UpdateState.Available(release)
                }
                release != null -> {
                    AppLogger.i(TAG, "up to date, remote=${release.tagName}")
                    _updateState.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
                }
                manual -> {
                    val msg = result.exceptionOrNull()?.message ?: "网络错误"
                    AppLogger.w(TAG, "update check failed: $msg")
                    _updateState.value = UpdateState.Failed(msg)
                }
                else -> {
                    AppLogger.d(TAG, "auto update check failed (silent): ${result.exceptionOrNull()?.message}")
                    _updateState.value = UpdateState.Idle
                }
            }
        }
    }

    /** 用户关闭更新弹窗：回 Idle 并抑制本次会话的自动弹窗（手动检查不受限）。 */
    fun dismissUpdate() {
        _updateState.value = UpdateState.Idle
        autoCheckSuppressed = true
    }

    companion object {
        private const val TAG = "AnythingVM"

        fun factory(application: Application): ViewModelProvider.Factory =
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }
}
