package com.masgzy.anything.data.office

import java.io.ByteArrayOutputStream

/**
 * 测试专用 CFB（OLE2）合成构造器 —— 为 CfbReader / 三个旧格式抽取器
 * 提供结构合法的最小容器，免依赖真实 Office 样本即可做回归。
 *
 * 布局（扇区从 0 起，文件偏移 = (n+1)*512）：
 *   大流数据 → 迷你流容器 → MiniFAT → 目录 → FAT
 * 全部链采用连续扇区（读侧按 FAT 链走，连续与碎片同路径）。
 * 大于等于 miniCutoff 的流走普通扇区，小于的进迷你流（64B 扇区）。
 */
internal class TestCfbBuilder(private val miniCutoff: Int = 4096) {

    private val streams = LinkedHashMap<String, ByteArray>()

    fun stream(name: String, data: ByteArray): TestCfbBuilder = apply { streams[name] = data }

    fun build(): ByteArray {
        val SECTOR = 512
        val MINI = 64
        val ENDOFCHAIN = 0xFFFFFFFE.toInt()
        val FREESECT = 0xFFFFFFFF.toInt()
        val FATSECT = 0xFFFFFFFD.toInt()

        fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

        // ---- 迷你流布局：拼接 + 记录各流起始迷你扇区 ----
        val miniStreams = streams.filterValues { it.size < miniCutoff }
        data class MiniAlloc(val start: Int, val sectors: Int)
        val miniAllocs = HashMap<String, MiniAlloc>()
        var miniSectorTotal = 0
        for ((name, data) in miniStreams) {
            val n = ceilDiv(data.size, MINI)
            miniAllocs[name] = MiniAlloc(miniSectorTotal, n)
            miniSectorTotal += n
        }
        val containerBytes = ByteArray(miniSectorTotal * MINI)
        for ((name, data) in miniStreams) {
            data.copyInto(containerBytes, miniAllocs[name]!!.start * MINI)
        }
        val containerSectors = ceilDiv(containerBytes.size, SECTOR)

        // ---- 扇区分配：大流 → 容器 → MiniFAT → 目录 → FAT ----
        var next = 0
        val bigAllocMap = HashMap<String, BigAlloc>()
        for ((name, data) in streams.filterValues { it.size >= miniCutoff }) {
            val n = ceilDiv(data.size, SECTOR)
            bigAllocMap[name] = BigAlloc(next, n, data)
            next += n
        }
        val containerStart = next
        next += containerSectors

        val miniFatSectors = if (miniSectorTotal > 0) ceilDiv(miniSectorTotal, SECTOR / 4) else 0
        val miniFatStart = next
        next += miniFatSectors

        val dirEntries = 1 + streams.size
        val dirSectors = ceilDiv(dirEntries * 128, SECTOR)
        val dirStart = next
        next += dirSectors

        // FAT 扇区数自洽迭代：FAT 自身也占扇区
        var fatSectors = 1
        while (next + fatSectors > fatSectors * (SECTOR / 4)) fatSectors++
        val fatStart = next
        val totalSectors = next + fatSectors

        // ---- FAT ----
        val fat = IntArray(fatSectors * (SECTOR / 4)) { FREESECT }
        for (s in fatStart until fatStart + fatSectors) fat[s] = FATSECT
        fun markChain(start: Int, count: Int) {
            for (i in 0 until count) {
                fat[start + i] = if (i == count - 1) ENDOFCHAIN else start + i + 1
            }
        }
        for ((_, a) in bigAllocMap) markChain(a.start, a.sectors)
        if (containerSectors > 0) markChain(containerStart, containerSectors)
        if (miniFatSectors > 0) markChain(miniFatStart, miniFatSectors)
        markChain(dirStart, dirSectors)

        // ---- MiniFAT ----
        val miniFat = IntArray(miniFatSectors * (SECTOR / 4)) { FREESECT }
        for ((_, a) in miniAllocs) {
            for (i in 0 until a.sectors) {
                miniFat[a.start + i] = if (i == a.sectors - 1) ENDOFCHAIN else a.start + i + 1
            }
        }

        // ---- 目录 ----
        val dirBytes = ByteArray(dirSectors * SECTOR)
        fun writeEntry(off: Int, name: String, type: Int, start: Int, size: Long) {
            val nameBytes = name.toByteArray(Charsets.UTF_16LE)
            nameBytes.copyInto(dirBytes, off)
            writeU16(dirBytes, off + 64, nameBytes.size + 2)
            dirBytes[off + 66] = type.toByte()
            writeU32(dirBytes, off + 116, start)
            writeU64(dirBytes, off + 120, size)
        }
        writeEntry(0, "Root Entry", 5,
            if (containerSectors > 0) containerStart else ENDOFCHAIN, containerBytes.size.toLong())
        var e = 1
        for ((name, data) in streams) {
            val big = bigAllocMap[name]
            if (big != null) {
                writeEntry(e * 128, name, 2, big.start, data.size.toLong())
            } else {
                val a = miniAllocs[name]!!
                writeEntry(e * 128, name, 2, a.start, data.size.toLong())
            }
            e++
        }

        // ---- 组装 ----
        val out = ByteArrayOutputStream(512 + totalSectors * SECTOR)
        val header = ByteArray(512)
        header.writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))
        writeU16(header, 0x1E, 9)  // 扇区 512B
        writeU16(header, 0x20, 6)  // 迷你扇区 64B
        writeU32(header, 0x2C, fatSectors.toLong())
        writeU32(header, 0x30, dirStart.toLong())
        writeU32(header, 0x38, miniCutoff.toLong())
        writeU32(header, 0x3C, if (miniFatSectors > 0) miniFatStart.toLong() else ENDOFCHAIN.toLong())
        writeU32(header, 0x40, miniFatSectors.toLong())
        writeU32(header, 0x44, ENDOFCHAIN.toLong()) // firstDifat
        writeU32(header, 0x48, 0)                   // numDifat
        for (i in 0 until 109) {
            writeU32(header, 0x4C + i * 4,
                if (i < fatSectors) (fatStart + i).toLong() else FREESECT.toLong())
        }
        out.write(header)

        fun writePadded(src: ByteArray, srcOff: Int, len: Int) {
            val s = ByteArray(SECTOR)
            val n = minOf(SECTOR, len)
            if (n > 0) System.arraycopy(src, srcOff, s, 0, n)
            out.write(s)
        }
        for ((_, a) in bigAllocMap) {
            for (i in 0 until a.sectors) {
                writePadded(a.bytes, i * SECTOR, a.bytes.size - i * SECTOR)
            }
        }
        for (i in 0 until containerSectors) {
            writePadded(containerBytes, i * SECTOR, containerBytes.size - i * SECTOR)
        }
        if (miniFatSectors > 0) {
            val mfBytes = ByteArray(miniFatSectors * SECTOR)
            for (i in miniFat.indices) writeU32(mfBytes, i * 4, miniFat[i].toLong())
            for (i in 0 until miniFatSectors) writePadded(mfBytes, i * SECTOR, SECTOR)
        }
        for (i in 0 until dirSectors) {
            writePadded(dirBytes, i * SECTOR, SECTOR)
        }
        val fatBytes = ByteArray(fatSectors * SECTOR)
        for (i in fat.indices) writeU32(fatBytes, i * 4, fat[i].toLong())
        for (i in 0 until fatSectors) writePadded(fatBytes, i * SECTOR, SECTOR)
        return out.toByteArray()
    }

    private class BigAlloc(val start: Int, val sectors: Int, val bytes: ByteArray)

    companion object {
        internal fun writeU16(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
        }

        internal fun writeU32(b: ByteArray, off: Int, v: Long) {
            writeU16(b, off, (v and 0xFFFF).toInt())
            writeU16(b, off + 2, ((v shr 16) and 0xFFFF).toInt())
        }

        internal fun writeU64(b: ByteArray, off: Int, v: Long) {
            writeU32(b, off, v and 0xFFFFFFFFL)
            writeU32(b, off + 4, (v ushr 32) and 0xFFFFFFFFL)
        }
    }
}
