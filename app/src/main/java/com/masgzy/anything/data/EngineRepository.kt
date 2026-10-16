package com.masgzy.anything.data

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import com.masgzy.anything.core.Engine
import com.masgzy.anything.data.office.LegacyOfficeParser
import com.masgzy.anything.core.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** UI 层搜索结果条目。 */
data class UiHit(
    val path: String,
    val name: String,
    val size: Long,
    val mtime: Long,
    val matched: String, // "name" | "content"
    val kind: String,    // "file" | "folder"
    val snippet: String,
) {
    val isFolder: Boolean get() = kind == "folder"
}

/** 一次搜索的解析结果（引擎 JSON → UI 状态的中间载体）。 */
private data class SearchResult(
    val hits: List<UiHit>,
    val elapsed: Long,
    val totalMatches: Long,
    val totalFiles: Long,
    val totalDirs: Long,
    val totalContent: Long,
)

/**
 * 单次搜索返回的条目上限（引擎侧允许的最大值也是 1000）。
 * 返回条数只影响列表可见范围；真实命中数由引擎 total_* 字段上报，
 * 页签计数与列表截断提示都以真实总数为准。
 */
private const val SEARCH_LIMIT = 1000L

/** 索引/扫描阶段。 */
enum class ScanPhase { IDLE, FIRST_BUILD, UPDATING }

/** 界面状态。 */
data class EngineUiState(
    val ready: Boolean = false,
    /** Go 引擎初始化失败的原因；null 表示引擎正常。引擎不可用时界面进入降级模式。 */
    val initError: String? = null,
    val phase: ScanPhase = ScanPhase.IDLE,
    /** 本次已扫描文件数（进度提示用）。 */
    val scanned: Long = 0,
    val query: String = "",
    /** 引擎返回的全部命中（文件名/目录/全文三类混合），UI 按 Tab 拆分展示。 */
    val hits: List<UiHit> = listOf(),
    val elapsedMs: Long = 0,
    /** 各类命中的真实总数（不受返回条数上限影响），供页签展示真实计数。 */
    val totalMatches: Long = 0,
    val totalFiles: Long = 0,
    val totalDirs: Long = 0,
    val totalContent: Long = 0,
    /** 最近一次扫描摘要（用于"索引更新完成"提示）。 */
    val lastSummary: ScanSummary? = null,
    val statusText: String = "就绪",
)

/** 一次扫描/索引更新的结果摘要。 */
data class ScanSummary(
    val files: Long,
    val added: Long,
    val updated: Long,
    val removed: Long,
    val durationMs: Long,
    val cancelled: Boolean,
    val firstBuild: Boolean,
) {
    /** 是否发生了实际变化（决定"索引更新完成"提示是否带变化数）。 */
    val changed: Boolean get() = added > 0 || updated > 0 || removed > 0
}

/**
 * Go 核心引擎的 Kotlin 封装。
 *
 * 鲁棒性设计：
 *  - 引擎在后台线程构造（构造时会同步恢复磁盘索引快照，避免主线程卡顿），
 *    构造失败（如个别设备 .so 加载异常）不会抛出崩溃，
 *    而是进入降级模式：界面可见 initError 提示，所有引擎调用安全跳过；
 *  - 就绪前 repoState.ready == false，自动扫描与手动按钮均被挡住；
 *  - 所有跨语言调用均 runCatching 包裹，JSON 解析失败不影响 UI 状态。
 *
 * 索引持久化（v0.3）：引擎指向 filesDir/engine_index，扫描收尾自动落盘，
 * 下次启动构造即恢复 —— 打开即可搜索，不再每次全量重建。
 * 全文索引（alpha14）：正文由 Go 侧 bleve scorch 持久化（filesDir/engine_index/fts）。
 *
 * 引擎 API（gobind lowerCamelCase 映射）：
 *   Engine(workers, dataDir) / setListener / startScan(optionsJson) /
 *   search(q, limit) / removePaths(pathsJson) / cancelScan / isScanning / stats
 */
class EngineRepository(context: Context) {

    companion object {
        /**
         * 进程级共享引擎。底层 scorch（bbolt）对索引目录持有文件锁：
         * 同目录出现第二个 Engine 实例会等锁失败导致全文索引降级，
         * 因此引擎必须单实例 —— Activity/ViewModel 重建一律复用。
         */
        @Volatile
        private var sharedEngine: Engine? = null
        private val engineLock = Any()

        fun acquireEngine(context: Context): Engine? {
            sharedEngine?.let { return it }
            synchronized(engineLock) {
                sharedEngine?.let { return it }
                val eng = runCatching {
                    Engine(0, File(context.applicationContext.filesDir, "engine_index").absolutePath)
                }.onFailure { t ->
                    android.util.Log.e("AnythingEngine", "Go 引擎初始化失败", t)
                }.getOrNull()
                if (eng != null) sharedEngine = eng
                return eng
            }
        }
    }

    private val appContext = context.applicationContext

    /** 引擎实例；null = 初始化失败，进入降级模式。后台线程构造，@Volatile 保证可见性。 */
    @Volatile
    private var engine: Engine? = null

    private val _state = MutableStateFlow(
        EngineUiState(statusText = "正在准备引擎…")
    )
    val state: StateFlow<EngineUiState> = _state

    private val listener = object : ProgressListener {
            override fun onProgress(phase: String?, done: Long) {
                // phase="index"：文档解析阶段（大文档解析期间文件计数会停顿，
                // 单独上报让用户看到进展而非卡住的扫描数）
                val text = if (phase == "index") "正在解析文档内容… 已解析 $done 篇"
                else "更新索引中… 已扫描 $done 项"
                _state.value = _state.value.copy(
                    scanned = done,
                    statusText = text,
                )
            }

            override fun onFinished(statsJSON: String?) {
                val o = runCatching { JSONObject(statsJSON ?: "{}") }.getOrNull()
                val summary = ScanSummary(
                    files = o?.optLong("files") ?: 0,
                    added = o?.optLong("added") ?: 0,
                    updated = o?.optLong("updated") ?: 0,
                    removed = o?.optLong("removed") ?: 0,
                    durationMs = o?.optLong("duration_ms") ?: 0,
                    cancelled = o?.optBoolean("cancelled") ?: false,
                    firstBuild = o?.optBoolean("first_build") ?: false,
                )
                _state.value = _state.value.copy(
                    phase = ScanPhase.IDLE,
                    lastSummary = summary,
                    statusText = when {
                        summary.cancelled -> "已取消，已索引 ${summary.files} 项"
                        summary.firstBuild -> "首次索引创建完成，共 ${summary.files} 项"
                        summary.changed ->
                            "索引更新完成：新增 ${summary.added}，更新 ${summary.updated}，移除 ${summary.removed}"
                        else -> "索引已是最新（${summary.files} 项）"
                    },
                )
            }

            override fun onError(phase: String?, message: String?) {
                _state.value = _state.value.copy(
                    statusText = "提醒: ${message ?: "未知错误"}"
                )
            }
    }

    init {
        // 后台线程构造引擎：NewEngine 内部会同步恢复磁盘索引快照，
        // 大索引（十万级条目）时避免阻塞主线程。就绪后置 ready=true，
        // ViewModel 监听该状态翻转并触发首次自动增量扫描。
        Thread {
            val eng = acquireEngine(appContext)
            engine = eng
            eng?.setListener(listener)
            // 旧版 Office（.doc/.xls/.ppt/.wps）宿主侧解析兜底；
            // 失败不影响引擎本身（解析器内部自捕获异常返回空串）
            eng?.runCatching { setExternalParser(LegacyOfficeParser()) }
                ?.onFailure { android.util.Log.e("AnythingEngine", "注册旧版 Office 解析器失败", it) }
            _state.value = _state.value.copy(
                ready = eng != null,
                initError = if (eng == null) {
                    "搜索引擎初始化失败，请尝试重新安装应用；若持续出现请提交反馈"
                } else null,
                statusText = if (eng == null) "引擎不可用" else "就绪",
            )
        }.start()
    }

    val hasEngine: Boolean
        get() = engine != null

    /**
     * 启动索引更新。
     * @param incremental true=增量（只处理变动，进入应用自动触发）；
     *                    false=全量重建（设置页"重建索引"）。
     */
    fun startScan(roots: Array<String>, incremental: Boolean) {
        val eng = engine ?: return
        if (_state.value.phase != ScanPhase.IDLE) return
        val opt = JSONObject().apply {
            put("roots", JSONArray(roots.toList()))
            put("mode", if (incremental) "incremental" else "full")
        }
        val first = incremental && isIndexEmpty()
        _state.value = _state.value.copy(
            phase = if (first) ScanPhase.FIRST_BUILD else ScanPhase.UPDATING,
            scanned = 0,
            statusText = if (first) "首次使用，正在为您建立文件索引…" else "更新索引中…",
        )
        runCatching { eng.startScan(opt.toString()) }
            .onFailure {
                _state.value = _state.value.copy(
                    phase = ScanPhase.IDLE,
                    statusText = "扫描启动失败: ${it.message}",
                )
            }
    }

    private fun isIndexEmpty(): Boolean = runCatching {
        // 必须读 indexed（索引内现存条目数，含快照恢复，与引擎 first_build 同口径）；
        // files 是"本轮遍历计数器"，冷启动尚未扫描时恒为 0，
        // 误读会导致每次进入应用都误判首次使用并弹出建索引遮罩。
        JSONObject(engine?.stats() ?: "{}").optLong("indexed", 1) == 0L
    }.getOrDefault(false)

    fun cancelScan() {
        val eng = engine ?: return
        runCatching { eng.cancelScan() }
        _state.value = _state.value.copy(statusText = "已请求取消…")
    }

    /** 直写状态栏文案（特权通道枚举等引擎外流程的进度提示）。 */
    fun setStatusText(text: String) {
        _state.value = _state.value.copy(statusText = text)
    }

    // ---- 扩展索引（Shizuku/Stellar 特权通道收录的 Android/data 等） ----

    /**
     * 用宿主枚举的完整清单替换扩展索引；引擎扫描中时返回 false（
     * 调用方应等 onFinished 后重试）。
     */
    fun replaceExternalEntries(root: String, dirs: List<String>, files: List<String>): Boolean {
        val eng = engine ?: return false
        val json = JSONObject().apply {
            put("root", root)
            put("dirs", JSONArray(dirs))
            put("files", JSONArray(files))
        }.toString()
        return runCatching { eng.replaceExternalEntries(json) }
            .onFailure { android.util.Log.w("AnythingEngine", "扩展索引更新失败", it) }
            .isSuccess
    }

    /** 扩展条目总数（文件+目录），供设置页展示。 */
    fun externalCount(): Int = runCatching {
        JSONObject(engine?.stats() ?: "{}").optInt("external", 0)
    }.getOrDefault(0)

    /** 搜索三类命中（文件名/目录名/全文），合并返回。 */
    suspend fun search(query: String) {
        val eng = engine
        if (query.isBlank() || eng == null) {
            _state.value = _state.value.copy(query = query, hits = emptyList())
            return
        }
        // 引擎调用 + 千条级 JSON 解析都在 Default 线程执行，不占主线程
        val parsed = withContext(Dispatchers.Default) {
            runCatching { eng.search(query, SEARCH_LIMIT) }.map { json ->
                val hits = mutableListOf<UiHit>()
                var elapsed = 0L
                var totalMatches = 0L
                var totalFiles = 0L
                var totalDirs = 0L
                var totalContent = 0L
                runCatching {
                    val obj = JSONObject(json)
                    elapsed = obj.optLong("elapsed_ms")
                    totalMatches = obj.optLong("total_matches")
                    totalFiles = obj.optLong("total_files")
                    totalDirs = obj.optLong("total_dirs")
                    totalContent = obj.optLong("total_content")
                    val arr = obj.optJSONArray("hits") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val h = arr.getJSONObject(i)
                        hits.add(
                            UiHit(
                                path = h.optString("path"),
                                name = h.optString("name"),
                                size = h.optLong("size"),
                                mtime = h.optLong("mtime"),
                                matched = h.optString("matched", "name"),
                                kind = h.optString("kind", "file"),
                                snippet = h.optString("snippet"),
                            )
                        )
                    }
                }
                SearchResult(hits, elapsed, totalMatches, totalFiles, totalDirs, totalContent)
            }
        }
        parsed.onSuccess { r ->
            _state.value = _state.value.copy(
                query = query, hits = r.hits, elapsedMs = r.elapsed,
                totalMatches = r.totalMatches, totalFiles = r.totalFiles,
                totalDirs = r.totalDirs, totalContent = r.totalContent,
                statusText = when {
                    r.hits.isEmpty() -> "无结果"
                    // 命中数超过返回上限时如实说明截断情况
                    r.totalMatches > r.hits.size ->
                        "命中 ${r.totalMatches} 项（显示前 ${r.hits.size}）· ${r.elapsed} ms"
                    else -> "命中 ${r.totalMatches} 项 · ${r.elapsed} ms"
                },
            )
        }.onFailure {
            _state.value = _state.value.copy(query = query, hits = emptyList())
        }
    }

    /** 删除文件后同步移出索引；返回成功删除的个数。 */
    fun removePaths(paths: List<String>): Int {
        val eng = engine ?: return 0
        if (paths.isEmpty()) return 0
        val json = JSONArray(paths).toString()
        val resp = runCatching { eng.removePaths(json) }.getOrNull() ?: return 0
        return runCatching { JSONObject(resp).optInt("removed", 0) }.getOrDefault(0)
    }

    /**
     * 用系统查看器打开命中的文件。
     * Android/data 等特权路径对应用进程不可读：先经 Shizuku/Stellar
     * 导出为缓存副本再打开（未授权或导出失败返回 false 由 UI 提示）。
     */
    suspend fun openFile(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            var file = File(path)
            if (!file.exists()) {
                if (!isPrivilegedPath(path)) return@runCatching false
                file = exportPrivileged(path) ?: return@runCatching false
            }
            val uri = fileUri(file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeOf(path))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** 分享文件（详情页"发送"）；特权路径先导出缓存副本。 */
    suspend fun shareFile(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            var file = File(path)
            if (!file.exists()) {
                if (!isPrivilegedPath(path)) return@runCatching false
                file = exportPrivileged(path) ?: return@runCatching false
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(path)
                putExtra(Intent.EXTRA_STREAM, fileUri(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(Intent.createChooser(intent, "发送").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }.getOrDefault(false)
    }

    /** 特权路径：Android 11+ 对应用隔离的目录（数据目录/OBB）。 */
    private fun isPrivilegedPath(path: String): Boolean {
        val base = Environment.getExternalStorageDirectory()?.absolutePath ?: return false
        return path.startsWith("$base/Android/data/") || path.startsWith("$base/Android/obb/")
    }

    /** Shizuku 特权导出缓存占用：（文件数, 总字节）。 */
    fun privilegedCacheStats(): Pair<Int, Long> {
        val dir = File(appContext.cacheDir, "shizuku_export")
        if (!dir.isDirectory) return 0 to 0L
        var count = 0
        var bytes = 0L
        dir.walkBottomUp().filter { it.isFile }.forEach {
            count++
            bytes += it.length()
        }
        return count to bytes
    }

    /** 清空特权导出缓存；返回是否成功（目录不存在视为成功）。 */
    fun clearPrivilegedCache(): Boolean = runCatching {
        File(appContext.cacheDir, "shizuku_export").deleteRecursively()
    }.getOrDefault(false)

    /** 经 Shizuku/Stellar 把特权路径导出为缓存副本；失败返回 null。 */
    private suspend fun exportPrivileged(path: String): File? {
        if (!ShizukuAccess.ready) return null
        val dir = File(appContext.cacheDir, "shizuku_export/${path.hashCode()}")
        return ShizukuAccess.exportFile(path, File(dir, File(path).name))
    }

    private fun fileUri(file: File) = FileProvider.getUriForFile(
        appContext, "${appContext.packageName}.fileprovider", file
    )

    private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "doc" -> "application/msword"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "xls" -> "application/vnd.ms-excel"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "ppt" -> "application/vnd.ms-powerpoint"
        "txt" -> "text/plain"
        else -> "*/*"
    }
}
