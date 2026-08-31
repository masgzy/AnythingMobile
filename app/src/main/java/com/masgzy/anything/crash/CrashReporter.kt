/*
 * 崩溃捕获与日志分享 —— 模式照抄 ImageToolbox
 * core/crash/.../GlobalExceptionHandler.kt：
 * 默认处理器兜底 -> 收集报告落盘 -> 拉起报告页 -> exitProcess(0)；
 * 任一环节失败回退系统默认处理器。报告页在独立进程（:crash）中运行，
 * 即使主进程因 OOM 崩溃也能正常展示/分享日志。
 */

package com.masgzy.anything.crash

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

object CrashReporter {

    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"

    /** 崩溃报告文件（应用私有 filesDir/crash/last_crash.txt）。 */
    fun reportFile(context: Context): File =
        File(File(context.applicationContext.filesDir, DIR), FILE)

    /** 是否存在未处理的崩溃报告（应用启动时提示用户分享）。 */
    fun hasPendingReport(context: Context): Boolean {
        val f = reportFile(context)
        return f.exists() && f.length() > 0
    }

    /** 清除崩溃报告（用户选择忽略或已分享后调用）。 */
    fun clear(context: Context) {
        runCatching { reportFile(context).delete() }
    }

    /**
     * 安装全局异常处理器。
     * 在 MainActivity.onCreate 最先调用（需早于任何可能崩溃的代码）。
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                saveReport(appContext, thread, throwable)
                launchReportActivity(appContext)
                exitProcess(0)
            }.getOrElse {
                // 自身流程失败（如磁盘满/进程将死）必须回退默认处理器，
                // 让系统弹窗/自动重启逻辑照常工作（ImageToolbox 同款兜底）
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun saveReport(context: Context, thread: Thread, throwable: Throwable) {
        val file = reportFile(context)
        file.parentFile?.mkdirs()
        file.writeText(buildReport(context, thread, throwable))
    }

    private fun launchReportActivity(context: Context) {
        context.startActivity(
            Intent(context, CrashReportActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
        )
    }

    /** 组装崩溃报告：设备信息 + 应用信息 + 线程 + 堆栈 + logcat 尾部。 */
    private fun buildReport(context: Context, thread: Thread, throwable: Throwable): String {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching {
            val pm = context.packageManager.getPackageInfo(context.packageName, 0)
            pm.versionName ?: "unknown"
        }.getOrDefault("unknown")

        return buildString {
            appendLine("===== AnythingMobile Crash Report =====")
            appendLine(
                "Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}",
            )
            appendLine("App: AnythingMobile v$version")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine("===== StackTrace =====")
            appendLine(stack)
            appendLine()
            appendLine("===== Logcat (tail 300) =====")
            appendLine(
                runCatching {
                    val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "300"))
                    p.inputStream.bufferedReader().use { it.readText() }
                }.getOrDefault("(logcat unavailable)"),
            )
        }
    }

    /** 通过系统分享面板把日志文件报出来（FileProvider，text/plain）。 */
    fun shareReport(activity: android.app.Activity) {
        val context = activity.applicationContext
        val file = reportFile(context)
        if (!file.exists()) return
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AnythingMobile 崩溃日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "分享崩溃日志"))
    }
}
