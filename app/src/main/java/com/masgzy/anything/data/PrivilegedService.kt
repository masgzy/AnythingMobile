package com.masgzy.anything.data

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.IOException

/**
 * Shizuku / Stellar 用户服务实现 —— 运行在特权进程（shell uid 2000 或 root）
 * 内，代替应用进程执行 find / cat 等对 Android/data 的操作。
 *
 * 服务生命周期由 Shizuku 服务端管理：
 *  - 实例由服务端反射构造（无参或 Context 单参构造，v13 优先后者）；
 *  - [destroy] 是服务端约定事务（AIDL 显式码 16777114），必须 System.exit；
 *  - 本进程不是合法 Android 应用进程，除文件 IO/Runtime.exec 外不要调用
 *    系统 API（Context 注册器/内容解析器均不可用）。
 */
@Keep
class PrivilegedService : IPrivilegedService.Stub {

    /** 无参构造：Shizuku 服务端反射构造（v13 以下只走这条）。 */
    @Suppress("unused")
    constructor() : super()

    /** Context 单参构造：Shizuku API v13 服务端优先使用。 */
    @Suppress("unused")
    constructor(context: Context) : super()

    /**
     * 枚举目录树：find -type 的输出逐行写入 fd。
     * find 为 toybox 内建命令，全 Android 设备可用。
     */
    override fun findFiles(path: String?, type: String?, fd: ParcelFileDescriptor?): Int {
        if (path.isNullOrBlank() || type.isNullOrBlank() || fd == null) return 22 // EINVAL
        val proc = try {
            Runtime.getRuntime().exec(arrayOf("find", path, "-type", type))
        } catch (_: IOException) {
            return 127 // 命令不可用
        }
        ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
            proc.inputStream.copyTo(out, 1 shl 16)
        }
        runCatching { proc.errorStream.close() }
        return runCatching { proc.waitFor() }.getOrDefault(-1)
    }

    /** cat 源文件内容写入 fd（调用方导出缓存副本用）。 */
    override fun catFile(path: String?, fd: ParcelFileDescriptor?): Int {
        if (path.isNullOrBlank() || fd == null) return 22 // EINVAL
        val proc = try {
            Runtime.getRuntime().exec(arrayOf("cat", path))
        } catch (_: IOException) {
            return 127
        }
        ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
            proc.inputStream.copyTo(out, 1 shl 16)
        }
        runCatching { proc.errorStream.close() }
        return runCatching { proc.waitFor() }.getOrDefault(-1)
    }

    /** Shizuku 服务端销毁约定：清理并退出用户服务进程。 */
    override fun destroy() {
        System.exit(0)
    }
}
