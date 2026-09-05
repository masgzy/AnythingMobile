package com.masgzy.anything.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.masgzy.anything.core.Engine
import com.masgzy.anything.core.ProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 *
 * 引擎 API（gobind lowerCamelCase 映射）：
 *   Engine(workers, dataDir) / setListener / startScan(optionsJson) /
 *   search(q, limit) / removePaths(pathsJson) / cancelScan / isScanning / stats
 */
class EngineRepository(context: Context) {

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
                _state.value = _state.value.copy(
                    scanned = done,
                    statusText = "更新索引中… 已扫描 $done 项",
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
            val eng = runCatching {
                Engine(0, File(appContext.filesDir, "engine_index").absolutePath)
            }.onFailure { t ->
                android.util.Log.e("AnythingEngine", "Go 引擎初始化失败", t)
            }.getOrNull()
            engine = eng
            eng?.setListener(listener)
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

    /** 搜索三类命中（文件名/目录名/全文），合并返回。 */
    fun search(query: String) {
        val eng = engine
        if (query.isBlank() || eng == null) {
            _state.value = _state.value.copy(query = query, hits = emptyList())
            return
        }
        runCatching { eng.search(query, 300) }.onSuccess { json ->
            val hits = mutableListOf<UiHit>()
            var elapsed = 0L
            runCatching {
                val obj = JSONObject(json)
                elapsed = obj.optLong("elapsed_ms")
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
            _state.value = _state.value.copy(
                query = query, hits = hits, elapsedMs = elapsed,
                statusText = if (hits.isEmpty()) "无结果" else "命中 ${hits.size} 项 · $elapsed ms",
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

    /** 用系统查看器打开命中的文件。 */
    fun openFile(path: String): Boolean = runCatching {
        val file = File(path)
        if (!file.exists()) return false
        val uri = fileUri(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(path))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
        true
    }.getOrDefault(false)

    /** 分享文件（详情页"发送"）。 */
    fun shareFile(path: String): Boolean = runCatching {
        val file = File(path)
        if (!file.exists()) return false
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
