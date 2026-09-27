package com.masgzy.anything.data.office

import android.util.Log
import com.masgzy.anything.core.ExternalParser
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 旧版二进制 Office（.doc/.xls/.ppt/.wps）正文抽取器 —— 宿主侧兜底解析器。
 *
 * 纯 Kotlin 零依赖实现（POI 在 Android 上体量与兼容性都不划算），只做
 * "把正文文本挖出来"这一件事：
 *  - .doc/.wps：WordDocument 流的 FIB → 表流的 CLX 分片表（piece table），
 *    逐分片按 UTF-16LE / 文档码页（lid 推断，中文=GB18030）解码；
 *  - .xls：Workbook 流记录扫描，BIFF8 拼装 SST 共享字符串表
 *    （含 CONTINUE 分段与段首 fHighByte 续字节），BIFF5 直取 LABEL；
 *  - .ppt：PowerPoint Document 流记录树，收集 TextCharsAtom（UTF-16）、
 *    TextBytesAtom（ANSI→GB18030）与 CString。
 *
 * 抽出的文本交给 Go 引擎统一清洗入库（控制字符/PUA/无效字节）。
 * 引擎解析流水线的多个 worker 会并发调用 [extractText] —— 本类无状态，线程安全。
 */
internal const val MAX_CHARS = 1 shl 20 // 单文档字符上限，与引擎 maxExtractText 同量级

class LegacyOfficeParser : ExternalParser {

    companion object {
        private const val TAG = "LegacyOffice"
    }

    override fun extractText(absPath: String?): String {
        if (absPath.isNullOrEmpty()) return ""
        return try {
            val bytes = java.io.File(absPath).takeIf { it.isFile && it.length() > 0 }
                ?.readBytes() ?: return ""
            val ext = absPath.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            val text = when (ext) {
                "doc", "wps" -> DocExtractor.extract(bytes)
                "xls" -> XlsExtractor.extract(bytes)
                "ppt" -> PptExtractor.extract(bytes)
                else -> return ""
            }
            if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
        } catch (t: Throwable) {
            Log.w(TAG, "抽取失败: $absPath: ${t.message}")
            "" // 返回空串 = 引擎跳过该文件
        }
    }
}

// ---------------------------------------------------------------------------
// 字节工具
// ---------------------------------------------------------------------------

internal object Bin {
    fun u16(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 2 > b.size) throw IOException("越界")
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }

    fun i32(b: ByteArray, off: Int): Int = u16(b, off) or (u16(b, off + 2) shl 16)
}

/** 按指定码页解码，非法字节替换为 U+FFFD（Go 侧入库时会再清洗丢弃）。 */
internal fun decodeWith(b: ByteArray, cs: Charset): String {
    val decoder = cs.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    return decoder.decode(java.nio.ByteBuffer.wrap(b)).toString()
}

/** 文档码页推断：中文简体/繁体、日、韩各就各位，其余退回 windows-1252。 */
internal fun charsetForLid(lid: Int): Charset = when (lid) {
    0x0804, 0x0C04, 0x1004 -> charset("GB18030")
    0x0404 -> charset("Big5")
    0x0411 -> charset("Shift_JIS")
    0x0412 -> charset("EUC-KR")
    else -> charset("windows-1252")
}

internal fun charset(name: String): Charset =
    try { Charset.forName(name) } catch (_: Throwable) { Charsets.ISO_8859_1 }

// ---------------------------------------------------------------------------
// .doc / .wps —— Word 二进制（HWPF 等价的最小实现）
// ---------------------------------------------------------------------------

internal object DocExtractor {

    fun extract(bytes: ByteArray): String {
        val cfb = CfbReader(bytes)
        val wd = cfb.getStream("WordDocument")
            ?: throw IOException("缺少 WordDocument 流")
        if (wd.size < 0x200) throw IOException("WordDocument 流过短")
        if (Bin.u16(wd, 0) != 0xA5EC) throw IOException("非 Word97 文件签名")

        val flags = Bin.u16(wd, 0x0A)
        if (flags and 0x0100 != 0) throw IOException("文档已加密")
        val tableName = if (flags and 0x0200 != 0) "1Table" else "0Table"
        val table = cfb.getStream(tableName, if (tableName == "1Table") "0Table" else "1Table")
            ?: throw IOException("缺少表流 $tableName")

        val lid = Bin.u16(wd, 0x06)
        val fcClx = Bin.i32(wd, 0x01A2)
        val lcbClx = Bin.i32(wd, 0x01A6)
        if (lcbClx <= 0 || fcClx < 0 || fcClx + lcbClx > table.size) {
            throw IOException("CLX 越界")
        }
        val clx = table.copyOfRange(fcClx, fcClx + lcbClx)
        val plc = parseClx(clx)

        // 字符总量：正文 + 脚注 + 页眉页脚 + 批注 + 尾注 + 文本框
        val total = intArrayOf(
            Bin.i32(wd, 0x4C), Bin.i32(wd, 0x50), Bin.i32(wd, 0x54),
            Bin.i32(wd, 0x5C), Bin.i32(wd, 0x60), Bin.i32(wd, 0x64), Bin.i32(wd, 0x68),
        ).sum()
        val cs = charsetForLid(lid)

        val out = StringBuilder(minOf(total, MAX_CHARS) + 64)
        val n = plc.size / 12 // 分片数：CP 数组 n+1 项 + PCD n 项
        for (i in 0 until n) {
            if (out.length >= MAX_CHARS) break
            val cpStart = Bin.i32(plc, i * 4)
            val cpEnd = Bin.i32(plc, (i + 1) * 4)
            val count = cpEnd - cpStart
            if (count <= 0) continue
            val fc = Bin.i32(plc, (n + 1) * 4 + i * 8 + 2)
            if (fc and 0x40000000 != 0) {
                // 压缩分片：1 字节/字符，码页解码
                val off = (fc and 0x3FFFFFFF) ushr 1
                if (off < 0 || off + count > wd.size) continue
                appendDocChars(out, decodeWith(wd.copyOfRange(off, off + count), cs))
            } else {
                // UTF-16LE 分片：2 字节/字符
                val off = fc and 0x3FFFFFFF
                if (off < 0 || off + count * 2 > wd.size) continue
                appendDocChars(out, String(wd, off, count * 2, Charsets.UTF_16LE))
            }
        }
        return out.toString()
    }

    /** CLX → PlcPcd 原始字节（跳过前面的 Prc 段）。 */
    private fun parseClx(clx: ByteArray): ByteArray {
        var pos = 0
        while (pos + 1 <= clx.size) {
            val t = clx[pos].toInt() and 0xFF
            when (t) {
                0x01 -> { // Prc：2 字节长度 + 数据
                    if (pos + 3 > clx.size) throw IOException("CLX 截断")
                    val cb = Bin.u16(clx, pos + 1)
                    pos += 3 + cb
                }
                0x02 -> { // Pcdt
                    if (pos + 5 > clx.size) throw IOException("CLX 截断")
                    val lcb = Bin.i32(clx, pos + 1)
                    if (lcb < 4 || pos + 5 + lcb > clx.size) throw IOException("PlcPcd 越界")
                    return clx.copyOfRange(pos + 5, pos + 5 + lcb)
                }
                else -> throw IOException("CLX 非法类型")
            }
        }
        throw IOException("CLX 缺少 Pcdt")
    }

    /**
     * Word 专用字符映射：段落/换行/单元格标记 → \n，连字符与锚点归一，
     * 域字符（0x13 指令开始 / 0x14 结果开始 / 0x15 结束）按状态机丢弃指令、
     * 保留结果。嵌套域：仅当所有外层域都处于结果段时才输出。
     */
    private fun appendDocChars(out: StringBuilder, s: String) {
        val fieldStack = ArrayList<Boolean>(4) // true = 当前域处于结果段
        for (ch in s) {
            val c = ch.code
            when (c) {
                0x13 -> fieldStack.add(false)
                0x14 -> if (fieldStack.isNotEmpty()) fieldStack[fieldStack.size - 1] = true
                0x15 -> fieldStack.removeAt(fieldStack.size - 1)
                else -> {
                    val ok = fieldStack.all { it }
                    if (!ok) continue
                    val mapped = when (c) {
                        0x0D, 0x07, 0x0B, 0x0C -> '\n'
                        0x1E -> '-'
                        0x09 -> '\t'
                        0x1F, 0x01, 0x02, 0x03, 0x04, 0x05, 0x08 -> null
                        else -> if (c < 0x20) null else ch
                    }
                    if (mapped != null && out.length < MAX_CHARS + 4096) out.append(mapped)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// .xls —— BIFF8/BIFF5
// ---------------------------------------------------------------------------

internal object XlsExtractor {

    private const val REC_BOF = 0x0809
    private const val REC_CONTINUE = 0x003C
    private const val REC_CODEPAGE = 0x0042
    private const val REC_SST = 0x00FC
    private const val REC_LABEL = 0x0204

    fun extract(bytes: ByteArray): String {
        val cfb = CfbReader(bytes)
        val wb = cfb.getStream("Workbook", "Book") ?: throw IOException("缺少 Workbook 流")
        if (wb.size < 8) throw IOException("Workbook 流过短")

        var biff8 = true
        run {
            // 首记录 BOF：vers 0x0600 = BIFF8，0x0500 = BIFF5
            if (Bin.u16(wb, 0) == REC_BOF && wb.size >= 8) {
                val vers = Bin.u16(wb, 4)
                biff8 = vers >= 0x0600
            }
        }

        var codepage = 936 // 中文场景兜底；多数文件自带 CODEPAGE 记录
        val out = StringBuilder()
        if (!biff8) {
            // BIFF5：直接收集 LABEL 记录
            var pos = 0
            while (pos + 4 <= wb.size) {
                val id = Bin.u16(wb, pos)
                val len = Bin.u16(wb, pos + 2)
                val body = pos + 4
                if (body + len > wb.size) break
                when (id) {
                    REC_CODEPAGE -> codepage = Bin.u16(wb, body)
                    REC_LABEL -> if (len >= 9) {
                        val cch = Bin.u16(wb, body + 6)
                        val high = (wb[body + 8].toInt() and 0x01) != 0
                        val dataOff = body + 9
                        val need = if (high) cch * 2 else cch
                        if (dataOff + need <= wb.size && cch > 0) {
                            appendText(out, wb, dataOff, need, high, codepage)
                        }
                    }
                }
                pos = body + len
            }
            return out.toString()
        }

        // BIFF8：拼装 SST（SST 记录 + 紧随的 CONTINUE 链）
        val segments = ArrayList<ByteArray>(8)
        var pos = 0
        var collecting = false
        while (pos + 4 <= wb.size) {
            val id = Bin.u16(wb, pos)
            val len = Bin.u16(wb, pos + 2)
            val body = pos + 4
            if (body + len > wb.size) break
            val payload = wb.copyOfRange(body, body + len)
            when {
                id == REC_CODEPAGE -> codepage = Bin.u16(wb, body)
                id == REC_SST -> { segments.clear(); segments.add(payload); collecting = true }
                id == REC_CONTINUE && collecting -> segments.add(payload)
                collecting -> collecting = false // SST 链结束
            }
            pos = body + len
        }
        if (segments.isEmpty()) throw IOException("缺少 SST")

        val cur = SegCursor(segments)
        cur.skip(4) // 总引用计数
        val unique = cur.u32()
        var emitted = 0
        for (i in 0 until unique) {
            if (out.length >= MAX_CHARS) break
            val s = readSstString(cur, codepage)
            if (s.isNotEmpty()) {
                if (emitted > 0) out.append('\n')
                out.append(s)
                emitted++
            }
        }
        return out.toString()
    }

    /**
     * SST 字符串（XLUnicodeRichExtendedString）：
     * cch:2, grbit:1(高位字节/富文本/扩展), [cRun:2], [cbExt:4],
     * 运行区 cRun*4 字节, 字符数据 cch 个（1 或 2 字节/字符）, 扩展区 cbExt 字节。
     * 字符数据跨 CONTINUE 段时，新段首字节是新的 fHighByte 标志。
     */
    private fun readSstString(cur: SegCursor, codepage: Int): String {
        val cch = cur.u16()
        val grbit = cur.u8()
        val high = grbit and 0x01 != 0
        val rich = grbit and 0x08 != 0
        val ext = grbit and 0x04 != 0
        val cRun = if (rich) cur.u16() else 0
        val cbExt = if (ext) cur.u32() else 0
        if (cRun > 0) cur.skip(cRun * 4)

        val sb = StringBuilder(cch.coerceIn(0, MAX_CHARS))
        var lowRun = java.io.ByteArrayOutputStream()
        var inHigh = high
        var left = cch
        while (left > 0) {
            if (cur.atSegStart) inHigh = (cur.u8() and 0x01) != 0 // 段首续标志
            if (inHigh) {
                if (lowRun.size() > 0) { flushLow(lowRun, sb, codepage); lowRun = java.io.ByteArrayOutputStream() }
                val b0 = cur.u8()
                val b1 = cur.u8()
                sb.append(((b1 shl 8) or b0).toChar())
            } else {
                lowRun.write(cur.u8())
            }
            left--
        }
        flushLow(lowRun, sb, codepage)
        if (cbExt > 0) cur.skip(cbExt)
        return sb.toString()
    }

    private fun flushLow(buf: java.io.ByteArrayOutputStream, sb: StringBuilder, codepage: Int) {
        if (buf.size() > 0) {
            sb.append(decodeWith(buf.toByteArray(), codepageCharset(codepage)))
            buf.reset()
        }
    }

    private fun codepageCharset(cp: Int): Charset = when (cp) {
        936 -> charset("GB18030")
        950 -> charset("Big5")
        932 -> charset("Shift_JIS")
        949 -> charset("EUC-KR")
        1252 -> charset("windows-1252")
        65001 -> Charsets.UTF_8
        else -> charset("GB18030")
    }

    private fun appendText(out: StringBuilder, wb: ByteArray, off: Int, need: Int, high: Boolean, codepage: Int) {
        if (high) {
            var i = 0
            while (i + 1 < need) {
                out.append(((wb[off + i + 1].toInt() and 0xFF) shl 8 or (wb[off + i].toInt() and 0xFF)).toChar())
                i += 2
            }
        } else {
            out.append(decodeWith(wb.copyOfRange(off, off + need), codepageCharset(codepage)))
        }
        out.append('\n')
    }
}

/**
 * 跨 CONTINUE 分段的字节游标：段耗尽后自动进入下一段。
 * [atSegStart] 为 true 表示"下一次读取位于新段开头"——字符数据续段时
 * 调用方须先读 1 字节 fHighByte 续标志（cch/cRun/cbExt 等非字符字段
 * 跨段则无此字节，直接读即可）。
 */
internal class SegCursor(private val segments: List<ByteArray>) {
    private var si = 0
    private var off = 0
    private var freshSeg = true

    val atSegStart: Boolean
        get() { ensure(); return freshSeg }

    private fun ensure() {
        while (off >= segments[si].size) {
            if (si + 1 >= segments.size) throw IOException("SST 越界")
            si++
            off = 0
            freshSeg = true
        }
    }

    fun u8(): Int {
        ensure()
        val v = segments[si][off].toInt() and 0xFF
        off++
        freshSeg = false
        return v
    }

    fun u16(): Int = u8() or (u8() shl 8)
    fun u32(): Int = u16() or (u16() shl 16)

    fun skip(n: Int) {
        for (i in 0 until n) u8()
    }
}

// ---------------------------------------------------------------------------
// .ppt —— PowerPoint 二进制记录树
// ---------------------------------------------------------------------------

internal object PptExtractor {

    private const val TEXT_CHARS = 0x0FA0 // UTF-16LE
    private const val TEXT_BYTES = 0x0FA8 // ANSI 单字节
    private const val CSTRING = 0x0FBA   // 记录树内字符串

    fun extract(bytes: ByteArray): String {
        val cfb = CfbReader(bytes)
        val doc = cfb.getStream("PowerPoint Document")
            ?: throw IOException("缺少 PowerPoint Document 流")
        val out = StringBuilder()
        walk(doc, 0, doc.size, out, 0)
        return out.toString()
    }

    private fun walk(b: ByteArray, start: Int, end: Int, out: StringBuilder, depth: Int) {
        if (depth > 32) return
        var pos = start
        while (pos + 8 <= end) {
            val opts = Bin.u16(b, pos)
            val type = Bin.u16(b, pos + 2)
            val len = Bin.i32(b, pos + 4)
            val body = pos + 8
            if (len < 0 || body + len > end) break
            if (opts and 0x0F == 0x0F) {
                walk(b, body, body + len, out, depth + 1) // 容器：递归
            } else when (type) {
                TEXT_CHARS -> if (len >= 2 && out.length < MAX_CHARS) {
                    out.append(String(b, body, len and 0x01.inv() and 0xFFFF, Charsets.UTF_16LE))
                    out.append('\n')
                }
                TEXT_BYTES -> if (len > 0 && out.length < MAX_CHARS) {
                    val seg = b.copyOfRange(body, body + len)
                    val ascii = seg.all { it >= 0 }
                    out.append(if (ascii) String(seg, Charsets.US_ASCII) else decodeWith(seg, charset("GB18030")))
                    out.append('\n')
                }
                CSTRING -> if (len > 0 && out.length < MAX_CHARS) {
                    val seg = b.copyOfRange(body, body + len)
                    // 真实文件里 CString 可能是 UTF-8 或 UTF-16LE，启发式选择
                    var s = runCatching {
                        charset("UTF-8").newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(seg)).toString()
                    }.getOrNull()
                    if (s.isNullOrEmpty() || s.any { it.code == 0 }) {
                        s = String(seg, 0, len and 0x01.inv(), Charsets.UTF_16LE)
                    }
                    out.append(s.filter { it.code != 0 })
                    out.append('\n')
                }
            }
            pos = body + len
        }
    }
}
