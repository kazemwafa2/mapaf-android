package af.mapaf.app.tileserver

import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * PMTiles v3 archive reader — serves vector tiles (MVT) from a LOCAL file only.
 * The app is fully independent: the compact basemap is bundled in the APK
 * (assets/basemap/af_compact.pmtiles) and the full package is installed from the
 * user's own GitHub release — no external tile server is contacted, ever.
 *
 * Header layout mirrors the archive writer: magic(8) then little-endian u64 fields
 * at 8/16/24/32/40/48/56/64, min/max zoom at 96/97. Logic follows the official
 * pmtiles spec (columnar varint directory, gzip blocks, Hilbert tile ids).
 */
class PmtilesSource(private val localPath: String) {

    data class Entry(val tileId: Long, val offset: Long, val length: Long, val runLength: Long)
    data class Header(
        val rootOffset: Long, val rootLength: Long,
        val leafOffset: Long, val leafLength: Long,
        val tileOffset: Long, val tileLength: Long,
        val minZoom: Int, val maxZoom: Int
    )

    @Volatile
    private var header: Header? = null
    @Volatile
    private var rootEntries: List<Entry>? = null
    private val leafCache = object : LinkedHashMap<Long, List<Entry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Entry>>): Boolean = size > 24
    }
    private val tileCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean = size > 384
    }
    private var localFile: RandomAccessFile? = null

    fun isReady(): Boolean = header != null

    @Synchronized
    fun open() {
        if (header != null) return
        val f = RandomAccessFile(localPath, "r")
        localFile = f
        val h = readBytes(8, 119)
        header = Header(
            rootOffset = h.long(0), rootLength = h.long(8),
            leafOffset = h.long(32), leafLength = h.long(40),
            tileOffset = h.long(48), tileLength = h.long(56),
            minZoom = h[88].toInt() and 0xFF, maxZoom = h[89].toInt() and 0xFF
        )
        rootEntries = deserializeDirectory(readBytes(h.long(0), h.long(8)))
    }

    fun close() { try { localFile?.close() } catch (_: Exception) {} }

    private fun readBytes(offset: Long, length: Long): ByteArray {
        val buf = ByteArray(length.toInt())
        localFile?.seek(offset)
        localFile?.readFully(buf)
        return buf
    }

    private fun ByteArray.long(off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (this[off + i].toLong() and 0xFF)
        return v
    }

    private fun gunzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(data)).use { gz -> gz.copyTo(bos) }
        return bos.toByteArray()
    }

    /** varint columnar directory format (v3) */
    private fun deserializeDirectory(raw: ByteArray): List<Entry> {
        val b = gunzip(raw)
        var i = 0
        fun varint(): Long {
            var r = 0L; var shift = 0
            while (true) {
                val c = b[i].toInt() and 0xFF; i++
                r = r or ((c and 0x7F).toLong() shl shift)
                if (c and 0x80 == 0) return r
                shift += 7
            }
        }
        val n = varint().toInt()
        val ids = LongArray(n)
        var lastId = 0L
        for (k in 0 until n) { lastId += varint(); ids[k] = lastId }
        val runs = LongArray(n); for (k in 0 until n) runs[k] = varint()
        val lens = LongArray(n); for (k in 0 until n) lens[k] = varint()
        val offs = LongArray(n)
        var prevOff = 0L; var prevLen = 0L
        for (k in 0 until n) {
            val v = varint()
            prevOff = if (k > 0 && v == 0L) prevOff + prevLen else v - 1
            prevLen = lens[k]
            offs[k] = prevOff
        }
        val out = ArrayList<Entry>(n)
        for (k in 0 until n) out.add(Entry(ids[k], offs[k], lens[k], runs[k]))
        return out
    }

    private fun findTile(entries: List<Entry>, tileId: Long): Entry? {
        var m = 0; var n = entries.size - 1
        while (m <= n) {
            val k = (n + m) shr 1
            val c = tileId - entries[k].tileId
            if (c > 0) m = k + 1
            else if (c < 0) n = k - 1
            else return entries[k]
        }
        if (n >= 0) {
            val e = entries[n]
            if (e.runLength == 0L) return e
            if (tileId - e.tileId < e.runLength) return e
        }
        return null
    }

    fun minZoom() = header?.minZoom ?: 0
    fun maxZoom() = header?.maxZoom ?: 14

    /** returns MVT bytes — gzip blocks from the archive are decompressed here so the
     *  HTTP layer always serves raw, decodable tiles (no Content-Encoding ambiguity) */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val cacheKey = "$z/$x/$y"
        synchronized(tileCache) { tileCache[cacheKey]?.let { return it } }
        open()
        val h = header ?: return null
        val tid = zxyToTileId(z, x, y)
        var dirOff = h.rootOffset; var dirLen = h.rootLength
        var dir: List<Entry>? = rootEntries
        for (depth in 0 until 4) {
            val entries = dir ?: synchronized(leafCache) { leafCache[dirOff] } ?: run {
                val d = deserializeDirectory(readBytes(dirOff, dirLen))
                synchronized(leafCache) { leafCache[dirOff] = d }
                d
            }
            val e = findTile(entries, tid) ?: return null
            if (e.runLength == 0L) {
                dirOff = h.leafOffset + e.offset; dirLen = e.length
                dir = null
                continue
            }
            if (e.length == 0L) return null
            var data = readBytes(h.tileOffset + e.offset, e.length)
            if (data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
                data = gunzip(data)
            }
            synchronized(tileCache) { tileCache[cacheKey] = data }
            return data
        }
        return null
    }

    companion object {
        /** PMTiles Hilbert curve tile id (mirrors official zxy_to_tileid) */
        fun zxyToTileId(z: Int, x: Int, y: Int): Long {
            var xx = x.toLong(); var yy = y.toLong()
            var acc = ((1L shl (z * 2)) - 1) / 3
            var a = z - 1
            while (a >= 0) {
                val s = 1L shl a
                val rx = s and xx
                val ry = s and yy
                acc += ((3L * rx) xor ry) shl a
                if (ry == 0L) {
                    if (rx != 0L) {
                        xx = s - 1 - xx
                        yy = s - 1 - yy
                    }
                    val t = xx; xx = yy; yy = t
                }
                a--
            }
            return acc
        }
    }
}
