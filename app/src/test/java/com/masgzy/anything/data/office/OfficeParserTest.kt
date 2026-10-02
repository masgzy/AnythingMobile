package com.masgzy.anything.data.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * 旧版 Office 解析回归网（P0-1）：
 * CfbReader（普通扇区/迷你扇区双路径）+ Doc/Xls/Ppt 三个抽取器 +
 * LegacyOfficeParser 端到端（文件读取 / 大小上限 / 损坏容错）。
 * 样本全部由 TestCfbBuilder 合成，结构对齐 MS-CFB / MS-DOC / BIFF8 / MS-PPT。
 */
class OfficeParserTest {

    // ---- CfbReader ----

    @Test
    fun `大流走普通扇区链且内容逐字节一致`() {
        val payload = ByteArray(5000) { (it % 251).toByte() } // > 4096 → 普通扇区
        val bytes = TestCfbBuilder().stream("Data", payload).build()
        val read = CfbReader(bytes).getStream("Data")
        assertTrue(read != null && read.contentEquals(payload))
    }

    @Test
    fun `小流走迷你扇区链且跨迷你扇区连续`() {
        val payload = ByteArray(200) { (it % 97).toByte() } // 200B = 4 个迷你扇区（64B）
        val small = byteArrayOf(1, 2, 3)
        val bytes = TestCfbBuilder().stream("Big2", payload).stream("Small", small).build()
        val r = CfbReader(bytes)
        assertTrue(r.getStream("Big2")!!.contentEquals(payload))
        assertTrue(r.getStream("Small")!!.contentEquals(small))
    }

    @Test
    fun `中文流名与多流共存`() {
        val b = byteArrayOf(9, 8, 7)
        val bytes = TestCfbBuilder()
            .stream("中文流", "中文流内容".toByteArray(Charsets.UTF_8))
            .stream("Other", b)
            .build()
        val r = CfbReader(bytes)
        assertTrue(r.getStream("中文流")!!.contentEquals("中文流内容".toByteArray(Charsets.UTF_8)))
        assertTrue(r.getStream("Other")!!.contentEquals(b))
    }

    @Test
    fun `缺失流返回null 非CFB抛IO异常`() {
        val bytes = TestCfbBuilder().stream("A", byteArrayOf(1)).build()
        assertNull(CfbReader(bytes).getStream("不存在的流"))
        try {
            CfbReader(ByteArray(512))
            throw AssertionError("应抛出 IOException")
        } catch (e: IOException) { /* 预期 */ }
    }

    // ---- .doc：FIB + CLX 分片表（压缩/UTF-16 双分片 + 域状态机） ----

    private fun buildDocBytes(): ByteArray {
        val wd = ByteArray(0x400)
        // FIB：wIdent=0xA5EC；lid=0x0804（GB18030）；flags=0（未加密，用 0Table）
        TestCfbBuilder.writeU16(wd, 0x00, 0xA5EC)
        TestCfbBuilder.writeU16(wd, 0x06, 0x0804)
        TestCfbBuilder.writeU16(wd, 0x0A, 0x0000)
        // ccpText（0x4C）= 两个分片字符总数；其余 ccp 全 0
        val piece1 = "合同项目会议纪要\r" // 压缩分片：1B/字符，GB18030
        val p1Bytes = piece1.toByteArray(charset("GB18030"))
        p1Bytes.copyInto(wd, 0x200) // 压缩文本放在 0x200 处
        val piece2 = "第2段\u0013DATE\u00142024\u0015末尾" // 域：指令丢、结果留
        val p2Bytes = piece2.toByteArray(Charsets.UTF_16LE)
        p2Bytes.copyInto(wd, 0x300)
        val totalChars = piece1.length + piece2.length
        TestCfbBuilder.writeU32(wd, 0x4C, totalChars.toLong())

        // 表流 CLX：Pcdt(0x02) + PlcPcd（CP 数组 n+1 项 + PCD n 项）
        val cp = byteArrayOf(0, 0, 0, 0) + intToBytes(piece1.length) + intToBytes(totalChars)
        val pcd1 = pcdCompressed(0x200, piece1.length)
        val pcd2 = pcdUtf16(0x300, piece2.length)
        val plc = cp + pcd1 + pcd2
        val clx = byteArrayOf(0x02) + intToBytes(plc.size) + plc
        TestCfbBuilder.writeU32(wd, 0x01A2, 0)          // fcClx
        TestCfbBuilder.writeU32(wd, 0x01A6, clx.size.toLong()) // lcbClx

        return TestCfbBuilder()
            .stream("WordDocument", wd) // 0x400 = 1024 < 4096 → 迷你流路径
            .stream("0Table", clx)
            .build()
    }

    @Test
    fun `doc 抽取 压缩分片GB18030加UTF16分片与域字符过滤`() {
        val text = DocExtractor.extract(buildDocBytes())
        assertEquals("合同项目会议纪要\n第2段2024末尾", text)
    }

    @Test
    fun `doc 加密文档报错而非乱码输出`() {
        val bytes = buildDocBytes().let { cfb ->
            // 把 WordDocument 流的加密标志位（0x0A 的 0x0100）置 1：
            // 流在迷你流容器里，直接改原始 CFB 不便 —— 重建：用大流版本
            val wd = ByteArray(0x400)
            TestCfbBuilder.writeU16(wd, 0x00, 0xA5EC)
            TestCfbBuilder.writeU16(wd, 0x0A, 0x0100) // fEncrypted
            TestCfbBuilder().stream("WordDocument", wd).stream("0Table", byteArrayOf(2, 0, 0, 0, 0)).build()
        }
        try {
            DocExtractor.extract(bytes)
            throw AssertionError("加密文档应抛 IOException")
        } catch (e: IOException) { /* 预期 */ }
    }

    // ---- .xls：BIFF8 SST（低/高字节 + CONTINUE 续段）与 BIFF5 LABEL ----

    private fun sstString(cch: Int, grbit: Int, data: ByteArray): ByteArray =
        byteArrayOf((cch and 0xFF).toByte(), ((cch shr 8) and 0xFF).toByte(), grbit.toByte()) + data

    private fun biffRecord(id: Int, body: ByteArray): ByteArray {
        val out = byteArrayOf((id and 0xFF).toByte(), ((id shr 8) and 0xFF).toByte(),
            (body.size and 0xFF).toByte(), ((body.size shr 8) and 0xFF).toByte())
        return out + body
    }

    @Test
    fun `xls BIFF8 SST 低字节GB18030高字节UTF16与CONTINUE续段`() {
        val s1 = sstString(3, 0x00, "合同表".toByteArray(charset("GB18030")))
        val s2 = sstString(2, 0x01, "会议".toByteArray(Charsets.UTF_16LE))
        // str3 "纪要备注"：SST 段内高字节输出前 2 字符，CONTINUE 段首标志 0x00
        // 切换低字节，后 2 字符以 GB18030 解码
        val str3Head = sstString(4, 0x01, "纪要".toByteArray(Charsets.UTF_16LE))
        val contBody = byteArrayOf(0x00) + "备注".toByteArray(charset("GB18030"))
        val sstBody = byteArrayOf(3, 0, 0, 0, 3, 0, 0, 0) + s1 + s2 + str3Head
        val wb = biffRecord(0x0809, byteArrayOf(0x00, 0x06, 0x05, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)) +
            biffRecord(0x0042, byteArrayOf(0xA8.toByte(), 0x03)) + // CODEPAGE 936
            biffRecord(0x00FC, sstBody) +
            biffRecord(0x003C, contBody)

        val bytes = TestCfbBuilder().stream("Workbook", wb).build()
        val text = XlsExtractor.extract(bytes)
        assertEquals("合同表\n会议\n纪要备注", text)
    }

    @Test
    fun `xls BIFF5 LABEL 直取`() {
        fun label(text: String, high: Boolean): ByteArray {
            val data = if (high) text.toByteArray(Charsets.UTF_16LE) else text.toByteArray(charset("GB18030"))
            val body = byteArrayOf(0, 0, 0, 0, 0, 0) + // row/col/xf
                byteArrayOf((text.length and 0xFF).toByte(), 0x00, if (high) 0x01 else 0x00) + data
            return biffRecord(0x0204, body)
        }
        val wb = biffRecord(0x0809, byteArrayOf(0x00, 0x05)) + // vers 0x0500 → BIFF5
            biffRecord(0x0042, byteArrayOf(0xA8.toByte(), 0x03)) +
            label("Hello", high = false) +
            label("表格", high = false)
        val bytes = TestCfbBuilder().stream("Book", wb).build()
        assertEquals("Hello\n表格\n", XlsExtractor.extract(bytes))
    }

    // ---- .ppt：记录树 + 三类文本 Atom ----

    @Test
    fun `ppt TextChars与TextBytes与CString三类Atom`() {
        val textChars = biffRecord(0x0FA0, "标题页面".toByteArray(Charsets.UTF_16LE))
        val textBytes = biffRecord(0x0FA8, "备注内容".toByteArray(charset("GB18030")))
        val cstring = biffRecord(0x0FBA, "结尾串".toByteArray(Charsets.UTF_8))
        // 一层容器把三个 Atom 包起来（opts 低 4 位 = 0xF 触发递归）
        val containerBody = textChars + textBytes + cstring
        val container = byteArrayOf(0x0F, 0x00, 0xE8, 0x03,
            (containerBody.size and 0xFF).toByte(), ((containerBody.size shr 8) and 0xFF).toByte(), 0, 0) +
            containerBody

        val bytes = TestCfbBuilder().stream("PowerPoint Document", container).build()
        assertEquals("标题页面\n备注内容\n结尾串\n", PptExtractor.extract(bytes))
    }

    // ---- LegacyOfficeParser 端到端（文件路径 / 大小上限 / 损坏容错） ----

    private fun tempFile(suffix: String, bytes: ByteArray): File =
        File.createTempFile("office_test", suffix).apply { writeBytes(bytes) }

    @Test
    fun `端到端 doc/xls/ppt 文件抽取`() {
        val doc = tempFile(".doc", buildDocBytes())
        val xls = tempFile(".xls", run {
            val wb = biffRecord(0x0809, byteArrayOf(0x00, 0x06)) +
                biffRecord(0x0042, byteArrayOf(0xA8.toByte(), 0x03)) +
                biffRecord(0x00FC, byteArrayOf(1, 0, 0, 0, 1, 0, 0, 0) +
                    sstString(2, 0x01, "表格".toByteArray(Charsets.UTF_16LE)))
            TestCfbBuilder().stream("Workbook", wb).build()
        })
        try {
            assertEquals("合同项目会议纪要\n第2段2024末尾", LegacyOfficeParser().extractText(doc.absolutePath))
            assertEquals("表格", LegacyOfficeParser().extractText(xls.absolutePath))
        } finally {
            doc.delete(); xls.delete()
        }
    }

    @Test
    fun `损坏文件与未知扩展名返回空串`() {
        val bad = tempFile(".doc", ByteArray(600) { 0x55 })
        val txt = tempFile(".txt", "纯文本".toByteArray())
        try {
            assertEquals("", LegacyOfficeParser().extractText(bad.absolutePath))
            assertEquals("", LegacyOfficeParser().extractText(txt.absolutePath))
            assertEquals("", LegacyOfficeParser().extractText(null))
            assertEquals("", LegacyOfficeParser().extractText(""))
        } finally {
            bad.delete(); txt.delete()
        }
    }

    @Test
    fun `超过大小上限的文件跳过正文`() {
        val big = tempFile(".doc", ByteArray(MAX_FILE_BYTES.toInt() + 1))
        try {
            assertEquals("", LegacyOfficeParser().extractText(big.absolutePath))
        } finally {
            big.delete()
        }
    }

    // ---- 工具 ----

    private fun intToBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

    /** 压缩分片 PCD：fc = 0x40000000 | (字节偏移 shl 1)。 */
    private fun pcdCompressed(byteOffset: Int, _count: Int): ByteArray {
        val fc = 0x40000000L or (byteOffset.toLong() shl 1)
        return byteArrayOf(0, 0) + intToBytes(fc.toInt()) + byteArrayOf(0, 0)
    }

    /** UTF-16 分片 PCD：fc = 字节偏移（bit30 = 0）。 */
    private fun pcdUtf16(byteOffset: Int, _count: Int): ByteArray {
        val fc = byteOffset.toLong()
        return byteArrayOf(0, 0) + intToBytes(fc.toInt()) + byteArrayOf(0, 0)
    }
}
