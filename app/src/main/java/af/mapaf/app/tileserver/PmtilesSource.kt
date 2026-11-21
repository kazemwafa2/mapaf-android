package af.mapaf.app.tileserver

import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * PMTiles v3 archive reader — serves vector tiles (MVT) from a single file,
 * either stored locally (offline package) or streamed over HTTP Range requests
 * (online mode, exactly like map.af's own tiles.map.af delivery).
 *
 * Logic mirrors the official pmtiles library (pmtiles/tile.py) and was validated
 * against af.pmtiles (16/16 random tiles byte-identical) before porting.
 */
class PmtilesSource(private val localPath: String?, val remoteUrl: String) {

    data class Entry(val tileId: Long, val offset: Long, val length: Long, val runLength: Long)
    data class Header(
        val rootOffset: Long, val rootLength: Long,
        val leafOffset: Long, val leafLength: Long,
        val tileOffset: Long, val tileLength: Long,
        val minZoom: Int, val maxZoom: Int
    )

    private var header: Header? = null
    private var rootEntries: List<Entry>? = null
    private val leafCache = object : LinkedHashMap<Long, List<Entry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Entry>>): Boolean = size > 24
    }
    private val tileCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean = size > 384
    }
    private var localFile: RandomAccessFile? = null

    @Synchronized
    fun open() {
        if (header != null) return
        if (localPath != null) {
            localFile = RandomAccessFile(localPath, "r")
        }
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
        val f = localFile
        return if (f != null) {
            val buf = ByteArray(length.toInt())
            f.seek(offset)
            f.readFully(buf)
            buf
        } else {
            val url = URL(remoteUrl)
            var attempt = 0
            while (true) {
                attempt++
                try {
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
                        setRequestProperty("User-Agent", USER_AGENT)
                        connectTimeout = 20000
                        readTimeout = 30000
                    }
                    val code = conn.responseCode
                    if (code == 200 || code == 206) {
                        return conn.inputStream.use { it.readBytes() }
                    }
                    conn.disconnect()
                    if (attempt >= 3) throw java.io.IOException("HTTP $code for range $offset+$length")
                } catch (e: Exception) {
                    if (attempt >= 3) throw e
                }
                Thread.sleep(300L * attempt)
            }
            @Suppress("UNREACHABLE_CODE") ByteArray(0)
        }
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

    /** returns raw (still gzip-compressed) MVT bytes, or null */
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
            val data = readBytes(h.tileOffset + e.offset, e.length)
            synchronized(tileCache) { tileCache[cacheKey] = data }
            return data
        }
        return null
    }

    companion object {
        const val USER_AGENT = "mapaf-android/1.0"

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
