package com.masgzy.anything.log

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内调试日志（alpha22）。
 *
 * 设计目标：让用户一键导出运行日志反馈，远程定位索引/搜索问题。
 *
 * 结构：
 *  - 内存环形缓冲（[CAPACITY] 条，超出淘汰最旧），进程内单例；
 *  - 运行时等级门控（[level]），低于等级的调用不进缓冲（logcat 始终输出，
 *    开发期用 Android Studio 看，用户反馈走缓冲）；
 *  - [export] 生成带时间戳文件头/尾的纯文本，经 FileProvider 系统分享。
 *
 * 线程模型：多引擎/工作线程并发写，ArrayDeque 全程持锁；读取快照同锁拷贝，
 * 单条日志 O(1)，量级（千条）无性能顾虑。
 */
object AppLogger {

    /** 缓冲容量：正常会话几十~几百条，留足一个完整首建索引会话的量。 */
    private const val CAPACITY = 3000

    /** 单条日志级别（沿用 android.util.Log 常量语义）。 */
    const val VERBOSE = Log.VERBOSE
    const val DEBUG = Log.DEBUG
    const val INFO = Log.INFO
    const val WARN = Log.WARN
    const val ERROR = Log.ERROR

    /** 当前记录等级；低于它的日志不进缓冲。默认 INFO。 */
    @Volatile
    var level: Int = INFO

    private val buffer = ArrayDeque<Entry>(CAPACITY)

    /** 一条日志。 */
    data class Entry(
        val time: Long,
        val level: Int,
        val tag: String,
        val message: String,
        val error: Throwable? = null,
    ) {
        private val ts: String =
            SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(time))

        private val levelChar: String = when (level) {
            VERBOSE -> "V"; DEBUG -> "D"; INFO -> "I"; WARN -> "W"; else -> "E"
        }

        fun render(): String = buildString {
            append(ts).append(' ').append(levelChar).append('/').append(tag)
            append(": ").append(message)
            if (error != null) {
                append('\n').append(Log.getStackTraceString(error))
            }
        }
    }

    fun v(tag: String, msg: String) = log(VERBOSE, tag, msg, null)
    fun d(tag: String, msg: String) = log(DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = log(INFO, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(ERROR, tag, msg, t)

    /** 核心入口：logcat 恒输出，缓冲按等级门控。 */
    private fun log(priority: Int, tag: String, msg: String, t: Throwable?) {
        Log.println(priority, tag, if (t != null) "$msg\n${Log.getStackTraceString(t)}" else msg)
        if (priority < level) return
        synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(Entry(System.currentTimeMillis(), priority, tag, msg, t))
        }
    }

    /** 快照（时间升序拷贝，UI 展示与导出共用）。 */
    fun snapshot(): List<Entry> = synchronized(buffer) { buffer.toList() }

    /** 当前缓冲条数。 */
    fun count(): Int = synchronized(buffer) { buffer.size }

    /** 清空缓冲。 */
    fun clear() = synchronized(buffer) { buffer.clear() }

    /** 等级的展示名（设置页与日志页共用）。 */
    fun levelName(p: Int): String = when (p) {
        VERBOSE -> "Verbose"; DEBUG -> "Debug"; INFO -> "Info"; WARN -> "Warn"; ERROR -> "Error"; else -> "?"
    }

    /**
     * 导出为文本文件并返回（filesDir/logs/export-<时间戳>.log），
     * 由调用方经 FileProvider 分享。文件头附环境信息便于对齐版本。
     */
    fun export(context: Context): File? = runCatching {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val file = File(dir, "anything-log-${System.currentTimeMillis()}.log")
        val entries = snapshot()
        file.bufferedWriter().use { w ->
            w.appendLine("AnythingMobile 日志导出")
            w.appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            w.appendLine("版本: ${runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrDefault("?")}  Android ${android.os.Build.VERSION.RELEASE}  ${android.os.Build.MODEL}")
            w.appendLine("条数: ${entries.size}  等级门控: ${levelName(level)}")
            w.appendLine("=".repeat(60))
            entries.forEach { w.appendLine(it.render()) }
        }
        file
    }.getOrNull()

    /** 系统分享面板发送日志文件（接收方可以是微信/TG/邮件等任意应用）。 */
    fun share(context: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "发送日志文件").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    }.getOrDefault(false)

    /** 清理历史导出文件（保留最近 [keep] 份）。 */
    fun pruneExports(context: Context, keep: Int = 3) {
        runCatching {
            File(context.filesDir, "logs").listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(keep)
                ?.forEach { it.delete() }
        }
    }
}
