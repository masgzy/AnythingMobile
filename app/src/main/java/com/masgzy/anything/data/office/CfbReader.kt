package com.masgzy.anything.data.office

import java.io.IOException

/**
 * CFB（OLE2 / 复合文件二进制格式）只读解析器 —— 旧版 Office（.doc/.xls/.ppt）
 * 的容器层。纯 Kotlin 实现、零依赖，仅覆盖本应用需要的子集：
 * 读取 FAT / MiniFAT 链式流与目录树中的指定流。
 *
 * 参考 [MS-CFB]：扇区 n 位于 (n+1)*sectorSize；v3 扇区 512B、v4 4096B；
 * 小于 4096B 的流走 64B 迷你扇区（mini stream 挂在根目录项上）。
 */
internal class CfbReader(private val data: ByteArray) {

    private class Entry(
        val name: String,
        val type: Int, // 0 empty, 1 storage, 2 stream, 5 root
        val start: Int,
        val size: Long,
    )

    private val sectorSize: Int
    private val miniCutoff: Long
    private val fat: IntArray
    private val miniFat: IntArray
    private val miniContainer: ByteArray // 根目录项链对应的迷你流容器
    private val entries: List<Entry>

    companion object {
        private const val ENDOFCHAIN = 0xFFFFFFFE.toInt()
        private const val FREESECT = 0xFFFFFFFF.toInt()
    }

    init {
        if (data.size < 512 || !hasCfbSignature(data)) {
            throw IOException("CFB: 非复合文件")
        }
        val sectorShift = readUShort(0x1E)
        require(sectorShift in 9..12) { "CFB: 非法扇区大小" }
        sectorSize = 1 shl sectorShift
        miniCutoff = readULong(0x38)
        val numFatSectors = readUInt(0x2C)
        val firstDirSector = readUInt(0x30)
        val firstMiniFat = readUInt(0x3C)
        val numMiniFat = readUInt(0x40)
        val firstDifat = readUInt(0x44)
        val numDifat = readUInt(0x48)

        // DIFAT：头部 109 项 + DIFAT 扇区链（每扇区末尾 u32 为下一 DIFAT 扇区）
        val fatSectorIds = ArrayList<Int>(numFatSectors.coerceIn(0, 1 shl 20) + 8)
        for (i in 0 until 109) {
            val v = readUInt(0x4C + i * 4)
            if (isDataSector(v)) fatSectorIds.add(v)
        }
        var difat = firstDifat
        var difatLeft = numDifat
        while (difat != ENDOFCHAIN && difatLeft > 0) {
            val base = sectorOffset(difat)
            if (base + sectorSize > data.size) break
            for (i in 0 until sectorSize / 4 - 1) {
                val v = readUIntAt(base + i * 4)
                if (isDataSector(v)) fatSectorIds.add(v)
            }
            difat = readUIntAt(base + sectorSize - 4)
            difatLeft--
        }

        // FAT：拼接全部 FAT 扇区
        fat = IntArray(fatSectorIds.size * (sectorSize / 4))
        var w = 0
        for (sid in fatSectorIds) {
            val base = sectorOffset(sid)
            if (base + sectorSize > data.size) break
            for (i in 0 until sectorSize / 4) {
                if (w >= fat.size) break
                fat[w++] = readUIntAt(base + i * 4)
            }
        }

        // 目录：firstDirSector 的 FAT 链，每 128B 一项（线性枚举即可）
        val dirBytes = readChain(firstDirSector, 0L, sectorSize, ::fatNext)
        entries = ArrayList()
        var p = 0
        while (p + 128 <= dirBytes.size) {
            val nameLen = u16(dirBytes, p + 64)
            val type = dirBytes[p + 66].toInt() and 0xFF
            if (nameLen >= 2 && nameLen <= 64 && (type == 1 || type == 2 || type == 5)) {
                val name = String(dirBytes, p, nameLen - 2, Charsets.UTF_16LE)
                entries.add(Entry(name, type, u32(dirBytes, p + 116), u64(dirBytes, p + 120)))
            }
            p += 128
        }
        if (entries.isEmpty() || entries[0].type != 5) throw IOException("CFB: 缺少根目录项")

        // MiniFAT 与迷你流容器（根目录项的普通扇区链）
        val miniFatIds = collectChainIds(firstMiniFat, ::fatNext)
        miniFat = IntArray(miniFatIds.size * (sectorSize / 4))
        var mw = 0
        for (sid in miniFatIds) {
            val base = sectorOffset(sid)
            if (base + sectorSize > data.size) break
            for (i in 0 until sectorSize / 4) {
                if (mw >= miniFat.size) break
                miniFat[mw++] = readUIntAt(base + i * 4)
            }
        }
        val root = entries[0]
        miniContainer = readChain(root.start, root.size, sectorSize, ::fatNext)
    }

    /** 按名称取流内容（候选名依序尝试）。 */
    fun getStream(vararg names: String): ByteArray? {
        for (want in names) {
            val e = entries.firstOrNull { it.type == 2 && it.name == want } ?: continue
            return if (e.size < miniCutoff) {
                readChain(e.start, e.size, 64, ::miniFatNext, from = miniContainer)
            } else {
                readChain(e.start, e.size, sectorSize, ::fatNext)
            }
        }
        return null
    }

    // ---- 内部实现 ----

    private fun isDataSector(v: Int) = v != ENDOFCHAIN && v != FREESECT &&
        v != 0xFFFFFFFC.toInt() && v != 0xFFFFFFFD.toInt()

    /** 签名 D0 CF 11 E0 A1 B1 1A E1（逐字节比较，避免 Long 补码换算）。 */
    private fun hasCfbSignature(b: ByteArray): Boolean {
        val sig = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        for (i in sig.indices) if (b[i] != sig[i]) return false
        return true
    }

    private fun sectorOffset(sector: Int): Long = (sector + 1L) * sectorSize

    private fun fatNext(sector: Int): Int = fat.getOrElse(sector) { ENDOFCHAIN }
    private fun miniFatNext(sector: Int): Int = miniFat.getOrElse(sector) { ENDOFCHAIN }

    /** 收集一条链上的全部扇区号（带环路保护）。 */
    private fun collectChainIds(start: Int, next: (Int) -> Int): List<Int> {
        val ids = ArrayList<Int>()
        var s = start
        val seen = HashSet<Int>()
        while (s != ENDOFCHAIN && s != FREESECT && seen.add(s)) {
            ids.add(s)
            s = next(s)
        }
        return ids
    }

    /**
     * 读取链式扇区流。[from] 非空时在该容器内按 unit 大小的"迷你扇区"读取
     * （链号 next 仍由调用方给出的函数解释），否则在文件本体上按标准扇区读。
     * size<=0 时读取整条链（目录/容器场景，长度未知）。
     */
    private fun readChain(
        start: Int,
        size: Long,
        unit: Int,
        next: (Int) -> Int,
        from: ByteArray? = null,
    ): ByteArray {
        val src = from ?: data
        val ids = collectChainIds(start, next)
        if (ids.isEmpty()) return ByteArray(0)
        val capped = if (size > 0) minOf(size, ids.size.toLong() * unit) else ids.size.toLong() * unit
        val total = capped.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val out = ByteArray(total)
        var written = 0
        for (sid in ids) {
            if (written >= total) break
            val off = if (from != null) sid.toLong() * unit else sectorOffset(sid)
            if (off < 0 || off + unit > src.size) break // 截断文件：返回已读部分
            val n = minOf(unit, total - written)
            System.arraycopy(src, off.toInt(), out, written, n)
            written += n
        }
        return if (written == out.size) out else out.copyOf(written)
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Int =
        u16(b, off) or (u16(b, off + 2) shl 16)

    private fun u64(b: ByteArray, off: Int): Long =
        (u32(b, off).toLong() and 0xFFFFFFFFL) or ((u32(b, off + 4).toLong() and 0xFFFFFFFFL) shl 32)

    private fun readUShort(off: Int): Int = u16(data, off)
    private fun readUInt(off: Int): Int = u32(data, off)
    private fun readUIntAt(off: Long): Int = u32(data, off.toInt())
    private fun readULong(off: Int): Long = u64(data, off)
}
