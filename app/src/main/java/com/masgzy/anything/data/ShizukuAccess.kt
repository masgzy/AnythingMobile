package com.masgzy.anything.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shizuku / Stellar 特权通道封装（扫描 Android/data 等受限目录）。
 *
 * 适配说明：Stellar（roro2239/Stellar）是 Shizuku 的深度定制分支，内置
 * Shizuku 兼容层 —— 使用官方 dev.rikka.shizuku API 与 ShizukuProvider 的
 * 应用无需修改代码即可被 Stellar 支持。因此本应用只接入一套官方 API，
 * 即可同时被 Shizuku 管理器与 Stellar 管理器授权。
 *
 * 特权执行：官方 Shizuku API v13 已将 Shizuku.newProcess 转为内部 API，
 * 标准姿势是 UserService（见 [IPrivilegedService]/[PrivilegedService]）——
 * 服务跑在 shell/root 身份的特权进程里，Stellar 兼容层同样支持
 * addUserService。大数据量（数万行 find 输出 / 整个文件内容）一律通过
 * ParcelFileDescriptor 写入应用私有缓存文件，绕开 binder 1MB 事务上限。
 *
 * 鲁棒性设计：
 *  - Shizuku binder 随时可能死亡/未连接，所有调用 runCatching 包裹；
 *  - 用户服务按需绑定 + 异步等待，未授权时所有能力调用安全返回 null；
 *  - 枚举与导出运行在 IO 调度器，整体带超时，特权进程挂起不拖垮 UI。
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

    /** newProcess 时代的最低 API 版本要求（v11+ 行为一致）。 */
    private const val API_VERSION_MIN = 11

    /** UserService 绑定所需的最低 Shizuku API 版本（v10 引入）。 */
    private const val USER_SERVICE_API_MIN = 10

    /** 用户服务实现版本：类实现变更时 +1，服务端会重启旧服务。 */
    private const val USER_SERVICE_VERSION = 1

    @Volatile
    private var initialized = false

    @Volatile
    private var packageName: String? = null

    @Volatile
    private var appContext: Context? = null

    /** 已连接的用户服务 binder；断连/服务端重启后由回调清空。 */
    @Volatile
    private var serviceBinder: IBinder? = null

    /** 注册 binder 生命周期与授权结果监听；在应用入口调用一次即可。 */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            val app = context.applicationContext
            appContext = app
            packageName = app.packageName
            runCatching {
                // sticky：binder 此后任何时刻到达都会回调，无需手动轮询
                Shizuku.addBinderReceivedListenerSticky { refresh() }
                Shizuku.addBinderDeadListener {
                    serviceBinder = null
                    _state.value = State()
                }
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
                    State(Status.GRANTED, Shizuku.getUid())
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

    // ---- 用户服务绑定 ----

    private val userServiceArgs: Shizuku.UserServiceArgs
        get() {
            val pkg = packageName ?: throw IllegalStateException("ShizukuAccess 未初始化")
            return Shizuku.UserServiceArgs(ComponentName(pkg, PrivilegedService::class.java.name))
                .daemon(false)
                .processNameSuffix("privileged")
                .version(USER_SERVICE_VERSION)
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceBinder = if (binder != null && binder.pingBinder()) binder else null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null
        }
    }

    /** 发起按需绑定；返回是否成功发出绑定请求。 */
    private fun ensureBound(): Boolean {
        if (serviceBinder?.pingBinder() == true) return true
        return runCatching {
            if (!Shizuku.pingBinder() || Shizuku.getVersion() < USER_SERVICE_API_MIN) return false
            Shizuku.bindUserService(userServiceArgs, connection)
            true
        }.getOrDefault(false)
    }

    /** 等待用户服务就绪；超时或不可用返回 null。 */
    private suspend fun awaitService(): IPrivilegedService? = withContext(Dispatchers.IO) {
        if (!ready || !ensureBound()) return@withContext null
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15)
        while (System.currentTimeMillis() < deadline) {
            val b = serviceBinder
            if (b != null && b.pingBinder()) {
                return@withContext runCatching {
                    IPrivilegedService.Stub.asInterface(b)
                }.getOrNull()
            }
            delay(100)
        }
        null
    }

    // ---- 对外能力：枚举 / 导出 ----

    /**
     * 枚举 [path] 树下的文件/目录名清单。
     * @param type "f"=文件，"d"=目录（toybox find 全设备可用）
     * @return 路径列表；失败（未授权/服务不可用/超时）返回 null，空树返回空表
     */
    suspend fun listTree(path: String, type: String): List<String>? =
        withContext(Dispatchers.IO) {
            val svc = awaitService() ?: return@withContext null
            val cacheDir = File(appContext?.cacheDir, "shizuku").apply { mkdirs() }
            val tmp = runCatching {
                File.createTempFile("find_${type}_", ".txt", cacheDir)
            }.getOrNull() ?: return@withContext null
            runCatching {
                withTimeout(180_000L) {
                    val pfd = ParcelFileDescriptor.open(
                        tmp,
                        ParcelFileDescriptor.MODE_READ_WRITE or
                            ParcelFileDescriptor.MODE_CREATE or
                            ParcelFileDescriptor.MODE_TRUNCATE,
                    )
                    val code = try {
                        svc.findFiles(path, type, pfd)
                    } finally {
                        runCatching { pfd.close() }
                    }
                    if (code == 0) tmp.readLines() else {
                        Log.w("ShizukuAccess", "find -type $type exit=$code")
                        null
                    }
                }
            }.getOrNull().also { tmp.delete() }
        }

    /**
     * 经特权进程把 [src]（应用进程不可直接读的路径）复制为缓存副本。
     * 成功返回副本文件；失败返回 null（调用方负责提示）。
     */
    suspend fun exportFile(src: String, dest: File): File? =
        withContext(Dispatchers.IO) {
            val svc = awaitService() ?: return@withContext null
            runCatching {
                withTimeout(120_000L) {
                    dest.parentFile?.mkdirs()
                    dest.delete() // 保证长度语义干净
                    val pfd = ParcelFileDescriptor.open(
                        dest,
                        ParcelFileDescriptor.MODE_READ_WRITE or
                            ParcelFileDescriptor.MODE_CREATE or
                            ParcelFileDescriptor.MODE_TRUNCATE,
                    )
                    val code = try {
                        svc.catFile(src, pfd)
                    } finally {
                        runCatching { pfd.close() }
                    }
                    if (code == 0 && dest.length() > 0) dest else {
                        dest.delete()
                        null
                    }
                }
            }.getOrNull()
        }
}
