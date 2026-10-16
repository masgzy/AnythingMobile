package com.masgzy.anything.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * SAF 目录管理（「目录」tab 的数据通道）。
 *
 * 授权闭环参照 NPatch（GPL-3.0，见 README 致谢）：
 * 裸 [Intent.ACTION_OPEN_DOCUMENT_TREE] 选择 → 回调里
 * [takePersistableUriPermission]（READ|WRITE）→ uri 字符串进
 * SharedPreferences（AppSettings.searchDirs）；重启后不集中校验，
 * 而是「用时重取权限 + exists() 探测」，失败即视为授权过期。
 *
 * 与 NPatch 的单输出目录不同，这里支持多目录，且要回答两个它没有的问题：
 *  1. treeUri 能否映射回物理路径（可映射且已授所有文件访问时，
 *     直接走引擎 StartScan 常规 File 遍历，快一个数量级）；
 *  2. 不可映射时的遍历：DocumentFile 树逐层 listFiles + 喂引擎 Ingest。
 */
object SafDirs {

    /** 注入条目上限（内存保护：条目 JSON 整体约 12MB 封顶）。 */
    const val MAX_ENTRIES = 80_000

    /** SAF 单文档读取上限：与引擎 maxParseSize（20MB）对齐。 */
    private const val MAX_DOC_BYTES = 20L * 1024 * 1024

    private val TAKE_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** 可全文抽取的扩展名（与引擎解析分发对齐：内置 OOXML/PDF + 宿主旧版 Office）。 */
    private val DOC_EXTS = setOf("docx", "pptx", "xlsx", "pdf", "doc", "xls", "ppt", "wps")

    // ---- 授权生命周期 ----

    /** 重取持久化授权（NPatch 同款「用时重取」）。false = 系统已回收授权。 */
    fun retake(context: Context, uriStr: String): Boolean = try {
        context.contentResolver.takePersistableUriPermission(Uri.parse(uriStr), TAKE_FLAGS)
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    /** 授权是否仍有效：权限可重取 + 目录仍存在（被删/被移走 → 失效）。 */
    fun isValid(context: Context, uriStr: String): Boolean = runCatching {
        val uri = Uri.parse(uriStr)
        if (!retake(context, uriStr)) return false
        DocumentFile.fromTreeUri(context, uri)?.exists() == true
    }.getOrDefault(false)

    /** 释放授权并从持久化列表语义上移除（UI 删除动作）。 */
    fun release(context: Context, uriStr: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uriStr),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    // ---- treeUri → 物理路径映射 ----

    /**
     * 尝试把 tree URI 映射为物理路径。ExternalStorageProvider 的树形
     * documentId 形如 "primary:Download/App"，主存储可还原为
     * /storage/emulated/0/Download/App；其余卷（外置 SD 卡等）返回 null，
     * 走 SAF 遍历通道。
     */
    fun treeToPath(context: Context, uriStr: String): String? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(Uri.parse(uriStr))
        val sep = docId.indexOf(':')
        if (sep <= 0) return@runCatching null
        val volume = docId.substring(0, sep)
        val rel = docId.substring(sep + 1)
        if (volume != "primary") return@runCatching null
        val base = Environment.getExternalStorageDirectory() ?: return@runCatching null
        if (rel.isEmpty()) base.absolutePath
        else "${base.absolutePath}/$rel"
    }.getOrNull()

    /** 目录展示名：树 id 尾段（"primary:" 根 → 主存储）。 */
    fun displayName(uriStr: String): String = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(Uri.parse(uriStr))
        val sep = docId.indexOf(':')
        val rel = if (sep >= 0) docId.substring(sep + 1) else docId
        rel.substringAfterLast('/').ifEmpty { "主存储" }
    }.getOrDefault(uriStr)

    /** 相对路径（用于列表副标题），如 Download/App。 */
    fun subPath(uriStr: String): String = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(Uri.parse(uriStr))
        val sep = docId.indexOf(':')
        if (sep >= 0) docId.substring(sep + 1).ifEmpty { "（根目录）" } else docId
    }.getOrDefault("")

    // ---- SAF 遍历 → Ingest 条目 ----

    /**
     * 遍历全部已授权目录，产出引擎 Ingest 条目 JSON。
     *
     * 递归用显式队列（DocumentFile.listFiles 已同步阻塞，深目录栈安全）；
     * 文档类文件（≤20MB）读出字节后经 [extractDoc] 抽正文（宿主转调引擎
     * ExtractDoc），其余条目只进名称/目录索引。返回 null = 遍历失败
     * （全部授权失效）；部分目录失效时跳过该目录继续（结果里自然缺失）。
     *
     * @param onProgress 已收录条目数（用于 UI 进度提示）
     */
    suspend fun collectEntries(
        context: Context,
        uriStrs: List<String>,
        extractDoc: (name: String, data: ByteArray) -> String?,
        onProgress: (Int) -> Unit = {},
    ): String? = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        var count = 0
        var anyOk = false

        for (uriStr in uriStrs) {
            val root = runCatching {
                if (!retake(context, uriStr)) return@runCatching null
                DocumentFile.fromTreeUri(context, Uri.parse(uriStr))?.takeIf { it.exists() }
            }.getOrNull() ?: continue
            anyOk = true

            // 显式队列：元素 = (目录节点, 相对路径)
            val queue = ArrayDeque<Pair<DocumentFile, String>>()
            queue.add(root to "")
            while (queue.isNotEmpty() && count < MAX_ENTRIES) {
                val (dir, rel) = queue.removeFirst()
                val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
                for (child in children) {
                    if (count >= MAX_ENTRIES) break
                    val name = child.name ?: continue
                    val childRel = "$rel$name/"
                    val obj = JSONObject()
                    obj.put("path", child.uri.toString())
                    obj.put("name", name)
                    obj.put("size", child.length())
                    obj.put("mtime", child.lastModified())
                    if (child.isDirectory) {
                        obj.put("kind", "folder")
                        arr.put(obj)
                        count++
                        queue.add(child to childRel)
                    } else {
                        obj.put("kind", "file")
                        // 全文抽取：文档扩展名 + 大小合规才读流（普通文件只进名称索引）
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext in DOC_EXTS && child.length() in 1..MAX_DOC_BYTES) {
                            val text = runCatching {
                                context.contentResolver.openInputStream(child.uri)
                                    ?.use { input -> input.readBytes() }
                                    ?.let { bytes -> extractDoc(name, bytes) }
                            }.getOrNull()
                            if (!text.isNullOrEmpty()) obj.put("content", text)
                        }
                        arr.put(obj)
                        count++
                    }
                    if (count % 500 == 0) onProgress(count)
                }
            }
            onProgress(count)
        }
        if (!anyOk) null else arr.toString()
    }
}
