package com.masgzy.anything.data

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Shizuku / Stellar 特权通道封装（扫描 Android/data 等受限目录）。
 *
 * 适配说明：Stellar（roro2239/Stellar）是 Shizuku 的深度定制分支，内置
 * Shizuku 兼容层 —— 使用官方 dev.rikka.shizuku API 与 ShizukuProvider 的
 * 应用无需修改代码即可被 Stellar 支持。因此本应用只接入一套官方 API，
 * 即可同时被 Shizuku 管理器与 Stellar 管理器授权。
 *
 * 鲁棒性设计：
 *  - Shizuku binder 随时可能死亡/未连接，所有调用 runCatching 包裹；
 *  - [Shizuku.newProcess] 在原版 Shizuku 已标记弃用但仍然可用，
 *    Stellar 明确重新启用并继续支持 —— 两个管理器行为一致；
 *  - 枚举与导出运行在 IO 调度器，整体带超时，特权进程挂起不会拖垮 UI；
 *  - 未初始化 / 未授权时所有能力调用返回安全值（null / false）。
 */
object ShizukuAccess {

    /** 服务可用状态。 */
    enum class Status {
        /** 未检测到运行中的 Shizuku / Stellar 服务（含未安装、未启动）。 */
        NOT_RUNNING,
        /** 服务在线但尚未授权本应用。 */
        NEED_PERMISSION,
        /** 已授权，可以执行特权操作。 */
        GRANTED,
    }

    data class State(
        val status: Status = Status.NOT_RUNNING,
        /** 特权进程身份（shell=2000 / root=0），-1 表示未知。 */
        val uid: Int = -1,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private const val REQUEST_CODE = 4201

    /** newProcess 所需的最低 Shizuku API 版本（v11 引入）。 */
    private const val API_VERSION_MIN = 11

    @Volatile
    private var initialized = false

    /** 注册 binder 生命周期与授权结果监听；在应用入口调用一次即可。 */
    fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            runCatching {
                // sticky：binder 此后任何时刻到达都会回调，无需手动轮询
                Shizuku.addBinderReceivedListenerSticky { refresh() }
                Shizuku.addBinderDeadListener { _state.value = State() }
                Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
            }.onFailure { Log.w("ShizukuAccess", "注册 Shizuku 监听失败", it) }
            refresh()
        }
    }

    /** 依据 binder 存活与授权结果重算状态。 */
    fun refresh() {
        val s = runCatching {
            when {
                !Shizuku.pingBinder() -> State()
                Shizuku.getVersion() < API_VERSION_MIN -> State()
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    State(Status.GRANTED, Shizuku.uid())
                else -> State(Status.NEED_PERMISSION)
            }
        }.getOrDefault(State())
        if (s != _state.value) _state.value = s
    }

    /** 发起授权弹窗（由 Shizuku / Stellar 管理器展示）。 */
    fun requestPermission() {
        runCatching {
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
            ) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        }
    }

    /** 是否已授权可用（特权枚举 / 导出的前置条件）。 */
    val ready: Boolean
        get() = _state.value.status == Status.GRANTED

    /**
     * 枚举 [path] 树下的文件/目录名清单。
     * @param type "f"=文件，"d"=目录（toybox find 全设备可用）
     * @return 路径列表；失败（未授权/无输出/超时）返回 null，空树返回空表
     */
    suspend fun listTree(path: String, type: String): List<String>? =
        withContext(Dispatchers.IO) {
            runCatching {
                withTimeout(180_000L) {
                    val p = Shizuku.newProcess(
                        arrayOf("find", path, "-type", type), null, null
                    )
                    val out = p.inputStream.bufferedReader().readLines()
                    val code = p.waitFor()
                    if (code == 0) out else {
                        Log.w(
                            "ShizukuAccess",
                            "find -type $type exit=$code out=${out.size}",
                        )
                        null
                    }
                }
            }.getOrNull()
        }

    /**
     * 经特权进程把 [src]（应用进程不可直接读的路径）复制为缓存副本。
     * 成功返回副本文件；失败返回 null（调用方负责提示）。
     */
    suspend fun exportFile(src: String, dest: File): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                withTimeout(120_000L) {
                    dest.parentFile?.mkdirs()
                    val p = Shizuku.newProcess(arrayOf("cat", src), null, null)
                    dest.outputStream().use { out ->
                        p.inputStream.copyTo(out, 1 shl 16)
                    }
                    val code = p.waitFor()
                    if (code == 0 && dest.length() > 0) dest else {
                        dest.delete()
                        null
                    }
                }
            }.getOrNull()
        }
}
