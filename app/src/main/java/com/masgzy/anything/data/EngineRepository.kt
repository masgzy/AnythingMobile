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
    val matched: String,
    val snippet: String,
)

/** 界面状态。 */
data class EngineUiState(
    val ready: Boolean = false,
    /** Go 引擎初始化失败的原因；null 表示引擎正常。引擎不可用时界面进入降级模式。 */
    val initError: String? = null,
    val scanning: Boolean = false,
    val progress: Long = 0,
    val statusText: String = "就绪",
    val query: String = "",
    val hits: List<UiHit> = listOf(),
)

/**
 * Go 核心引擎的 Kotlin 封装。
 *
 * 鲁棒性设计：
 *  - 引擎构造失败（如个别设备 .so 加载异常）不会抛出崩溃，
 *    而是进入降级模式：界面可见 initError 提示，所有引擎调用安全跳过。
 *  - 所有跨语言调用均 runCatching 包裹，JSON 解析失败不影响 UI 状态。
 *
 * 命名说明：gobind 依据官方文档把 Go 方法映射为 lowerCamelCase，
 * 并把 NewT(...) 形式的函数转换为构造器：
 *   NewEngine(0)        -> Engine(0)（Go 侧返回 error 时抛异常）
 *   StartScan(json)     -> startScan(json)
 *   Search(q, limit)    -> search(q, limit)
 *   CancelScan()        -> cancelScan()
 *   IsScanning()        -> isScanning()
 *   AddDocumentText(..) -> addDocumentText(..)
 *   Stats()             -> stats()
 */
class EngineRepository(context: Context) {

    private val appContext = context.applicationContext

    /** 引擎实例；null = 初始化失败，进入降级模式。 */
    private val engine: Engine? = runCatching {
        Engine(0) // workers=0 => 引擎按 CPU 核数自动决定
    }.onFailure { t ->
        android.util.Log.e("AnythingEngine", "Go 引擎初始化失败", t)
    }.getOrNull()

    private val _state = MutableStateFlow(
        EngineUiState(
            ready = engine != null,
            initError = if (engine == null) {
                "搜索引擎初始化失败，请尝试重新安装应用；若持续出现请提交反馈"
            } else null,
        )
    )
    val state: StateFlow<EngineUiState> = _state

    init {
        engine?.setListener(object : ProgressListener {
            override fun onProgress(phase: String?, done: Long) {
                _state.value = _state.value.copy(
                    progress = done,
                    statusText = if (phase == "scan") "正在扫描索引… $done" else "正在处理… $done",
                )
            }

            override fun onFinished(statsJSON: String?) {
                val stats = runCatching { JSONObject(statsJSON ?: "{}") }.getOrNull()
                val files = stats?.optLong("files") ?: 0
                val cancelled = stats?.optBoolean("cancelled") ?: false
                val duration = stats?.optLong("duration_ms") ?: 0
                _state.value = _state.value.copy(
                    scanning = false,
                    statusText = when {
                        cancelled -> "已取消，已索引 $files 个文件"
                        else -> "索引完成，共 $files 个文件（${formatDuration(duration)}）"
                    },
                )
            }

            override fun onError(phase: String?, message: String?) {
                _state.value = _state.value.copy(
                    statusText = "提醒: ${message ?: "未知错误"}"
                )
            }
        })
    }

    fun startScan(roots: Array<String>) {
        val eng = engine ?: return
        if (_state.value.scanning) return
        val rootsJson = JSONArray(roots.toList()).toString()
        _state.value = _state.value.copy(scanning = true, progress = 0, statusText = "开始扫描…")
        runCatching { eng.startScan(rootsJson) }
            .onFailure {
                _state.value = _state.value.copy(
                    scanning = false,
                    statusText = "扫描启动失败: ${it.message}"
                )
            }
    }

    fun cancelScan() {
        val eng = engine ?: return
        runCatching { eng.cancelScan() }
        _state.value = _state.value.copy(statusText = "已请求取消…")
    }

    fun search(query: String) {
        val eng = engine
        if (query.isBlank()) {
            _state.value = _state.value.copy(query = query, hits = emptyList())
            return
        }
        if (eng == null) {
            _state.value = _state.value.copy(query = query, hits = emptyList())
            return
        }
        val respJson = runCatching { eng.search(query, 100) }
        respJson.onSuccess { json ->
            val hits = mutableListOf<UiHit>()
            runCatching {
                val obj = JSONObject(json)
                val arr = obj.optJSONArray("hits") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val h = arr.getJSONObject(i)
                    hits.add(
                        UiHit(
                            path = h.optString("path"),
                            name = h.optString("name"),
                            size = h.optLong("size"),
                            mtime = h.optLong("mtime"),
                            matched = h.optString("matched"),
                            snippet = h.optString("snippet"),
                        )
                    )
                }
            }
            val elapsed = runCatching {
                JSONObject(json).optLong("elapsed_ms")
            }.getOrDefault(0)
            _state.value = _state.value.copy(query = query, hits = hits)
            if (hits.isEmpty()) {
                _state.value = _state.value.copy(statusText = "无结果")
            } else {
                _state.value = _state.value.copy(
                    statusText = "命中 ${hits.size} 条 · $elapsed ms"
                )
            }
        }.onFailure {
            _state.value = _state.value.copy(query = query, hits = emptyList())
        }
    }

    /** 用系统查看器打开命中的文件。 */
    fun openFile(path: String): Boolean = runCatching {
        val file = File(path)
        if (!file.exists()) return false
        val uri = FileProvider.getUriForFile(
            appContext, "${appContext.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(path))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
        true
    }.getOrDefault(false)

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

    private fun formatDuration(ms: Long): String = when {
        ms >= 60_000 -> "${ms / 60_000} 分 ${(ms % 60_000) / 1000} 秒"
        ms >= 1_000 -> "${ms / 1000.0} 秒"
        else -> "$ms ms"
    }
}
