package af.mapaf.app.nav

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fully-independent offline router over the road graph extracted from map.af's own
 * basemap file (af.pmtiles → OpenMapTiles transportation layer → scripts/build_graph.py).
 * Binary 'MAFGRPH4' layout (all little-endian):
 *   magic | i32 nodes, i32 rows, i32 cells
 *   nodes: n × u32 packed (latDelta<<14 | lngDelta)  — e7 units within cell
 *   cellAnchors: c × (i32 lat_e7, i32 lng_e7)
 *   cellKeys: c × i64 (ascending) ; cellStart: (c+1) × i32
 *   rows: e × u64 = a<<42 | b<<20 | meters<<4 | class<<1 | tw   (tw=1 → two-way)
 */
class GraphRouter {

    object Cls {
        const val MOTORWAY = 0; const val TRUNK = 1; const val PRIMARY = 2
        const val SECONDARY = 3; const val TERTIARY = 4; const val MINOR = 5
    }

    private var latE7 = IntArray(0)
    private var lngE7 = IntArray(0)
    private var offsets = IntArray(0)
    private var edgeTo = IntArray(0)
    private var edgeM = IntArray(0)
    private var edgeCls = ByteArray(0)
    private var cellKeys = LongArray(0)
    private var cellStart = IntArray(0)
    private var nNodes = 0

    // component labeling (for snapping onto the main network)
    private var compSize = IntArray(0)

    // A* working arrays
    private var dist = FloatArray(0)
    private var stamp = IntArray(0)
    private var epoch = 0

    val loaded: Boolean get() = nNodes > 0
    var nodeCount: Int = 0; private set

    private val speedCar = floatArrayOf(30f, 24f, 18f, 14f, 11f, 8f)
    private val speedFoot = floatArrayOf(0f, 0f, 1.3f, 1.3f, 1.3f, 1.3f)

    fun load(file: File): Boolean = try {
        load(ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN))
    } catch (_: Exception) { false }

    fun loadFromBytes(bytes: ByteArray): Boolean = try {
        load(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
    } catch (_: Exception) { false }

    private fun load(buf: ByteBuffer): Boolean {
        val magic = ByteArray(8)
        buf.get(magic)
        if (!magic.contentEquals("MAFGRPH4".toByteArray())) return false
        val n = buf.int; val rows = buf.int; val c = buf.int
        nNodes = n; nodeCount = n
        latE7 = IntArray(n); lngE7 = IntArray(n)
        // nodes are grouped by cell: decode via cellstart
        cellStart = IntArray(c + 1)
        // read nodes packed
        val packed = IntArray(n)
        for (i in 0 until n) packed[i] = buf.int
        val aLat = IntArray(c); val aLng = IntArray(c)
        for (i in 0 until c) { aLat[i] = buf.int; aLng[i] = buf.int }
        cellKeys = LongArray(c)
        for (i in 0 until c) cellKeys[i] = buf.long
        for (i in 0..c) cellStart[i] = buf.int
        for (i in 0 until n) {
            val cl = cellOf(i)
            latE7[i] = aLat[cl] + (packed[i] ushr 14)
            lngE7[i] = aLng[cl] + (packed[i] and 0x3FFF)
        }
        // expand rows into CSR (two-way rows produce both directions)
        val src = IntArray(rows); val dst = IntArray(rows)
        val mArr = IntArray(rows); val clsArr = ByteArray(rows)
        var m2 = 0
        for (k in 0 until rows) {
            val r = buf.long
            val aa = (r ushr 42).toInt()
            val bb = ((r ushr 20) and 0x3FFFFFL).toInt()
            val mm = ((r ushr 4) and 0xFFFFL).toInt()
            val cc2 = ((r ushr 1) and 7L).toInt()
            val isTwoWay = (r and 1L) == 1L
            src[m2] = aa; dst[m2] = bb; mArr[m2] = mm; clsArr[m2] = cc2.toByte(); m2++
            if (isTwoWay) { src[m2] = bb; dst[m2] = aa; mArr[m2] = mm; clsArr[m2] = cc2.toByte(); m2++ }
        }
        cnt = IntArray(n)
        for (k in 0 until m2) cnt[src[k]]++
        offsets = IntArray(n + 1)
        for (i in 0 until n) offsets[i + 1] = offsets[i] + cnt[i]
        val pos = offsets.copyOfRange(0, n)
        edgeTo = IntArray(m2); edgeM = IntArray(m2); edgeCls = ByteArray(m2)
        for (k in 0 until m2) {
            val p = pos[src[k]]
            edgeTo[p] = dst[k]; edgeM[p] = mArr[k]; edgeCls[p] = clsArr[k]
            pos[src[k]] = p + 1
        }
        // component sizes (iterative union-find)
        compSize = IntArray(n)
        val par = IntArray(n) { it }
        fun find(x0: Int): Int {
            var x = x0
            while (par[x] != x) { par[x] = par[par[x]]; x = par[x] }
            return x
        }
        for (k in 0 until m2) {
            val ru = find(src[k]); val rv = find(dst[k])
            if (ru != rv) par[max(ru, rv)] = min(ru, rv)
        }
        val sz = IntArray(n)
        for (i in 0 until n) sz[find(i)]++
        for (i in 0 until n) compSize[i] = sz[find(i)]
        dist = FloatArray(n); stamp = IntArray(n); prevArr = IntArray(n)
        return true
    }

    private var cnt = IntArray(0)

    private fun cellOf(i: Int): Int {
        var lo = 0; var hi = cellKeys.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cellStart[mid] <= i) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun latD(i: Int) = latE7[i] / 1e7
    private fun lngD(i: Int) = lngE7[i] / 1e7

    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
        val a2 = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(sqrt(a2))
    }

    /** nearest node that sits on a real (main) component; expanding ring search */
    fun snap(lat: Double, lng: Double): Int {
        val laC = floor(lat * 1024.0).toInt()
        val loC = floor(lng * 1024.0).toInt()
        var fallback = -1; var fallbackD = Double.MAX_VALUE
        for (r in 0..12) {
            var best = -1; var bestD = Double.MAX_VALUE
            for (dx in -r..r) {
                for (dy in -r..r) {
                    if (r > 0 && max(abs(dx), abs(dy)) != r) continue
                    val key = (((laC + dy) + 32768).toLong() shl 20) or ((loC + dx) + 32768).toLong()
                    val idx = cellIndex(key) ?: continue
                    for (k in cellStart[idx] until cellStart[idx + 1]) {
                        val nid = k  // nodes are stored in cell order — global id == position
                        val d = haversine(lat, lng, latD(nid), lngD(nid))
                        if (d < bestD) { bestD = d; best = nid }
                        if (d < fallbackD) { fallbackD = d; fallback = nid }
                    }
                }
            }
            if (best >= 0) {
                // prefer main-network nodes (avoid isolated fragments)
                if (compSize[best] >= 5000) return best
                if (fallback >= 0 && compSize[fallback] >= 5000 && fallbackD < bestD + 150.0) return fallback
                return best
            }
        }
        return fallback
    }

    private fun cellIndex(key: Long): Int? {
        var lo = 0; var hi = cellKeys.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cellKeys[mid] == key -> return mid
                cellKeys[mid] < key -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return null
    }

    data class Result(
        val points: List<DoubleArray>,
        val totalMeters: Double,
        val seconds: Double
    )

    fun route(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, foot: Boolean): Result? {
        if (!loaded) return null
        val s = snap(fromLat, fromLng)
        val t = snap(toLat, toLng)
        if (s < 0 || t < 0) return null
        if (s == t) return Result(listOf(doubleArrayOf(latD(s), lngD(s)), doubleArrayOf(latD(t), lngD(t))), 0.0, 0.0)
        epoch++
        val spd = if (foot) speedFoot else speedCar
        val maxSpd = if (foot) 1.3f else 30f
        val pq = PriorityQueue<Long>()
        dist[s] = 0f; stamp[s] = epoch
        val h0 = heuristic(s, t, maxSpd)
        pq.add(((h0.toRawBits().toLong() and 0xFFFFFFFFL) shl 32) or s.toLong())
        var found = false
        var polled = 0
        while (pq.isNotEmpty()) {
            val v = pq.poll()
            val u = (v and 0xFFFFFFFFL).toInt()
            val fBits = ((v ushr 32) and 0xFFFFFFFFL).toInt()
            if (stamp[u] != epoch || fBits.toFloat().toRawBits() != dist[u].toRawBits()) continue
            if (u == t) { found = true; break }
            polled++
            if (polled > 3_500_000) break
            var k = offsets[u]; val end = offsets[u + 1]
            while (k < end) {
                val w = edgeTo[k]
                val cls = edgeCls[k].toInt() and 0xFF
                val sp = spd[cls]
                if (sp > 0f) {
                    val du = dist[u] + edgeM[k] / sp
                    if (stamp[w] != epoch || du < dist[w]) {
                        stamp[w] = epoch
                        dist[w] = du
                        prevArr[w] = u
                        val h = heuristic(w, t, maxSpd)
                        val f = du + h
                        pq.add(((f.toRawBits().toLong() and 0xFFFFFFFFL) shl 32) or w.toLong())
                    }
                }
                k++
            }
        }
        if (!found) return null
        val path = ArrayList<IntArray>()
        var cur = t
        var guard = 0
        while (cur != -1 && guard <= nNodes) {
            path.add(intArrayOf(latE7[cur], lngE7[cur]))
            cur = prevOf(cur)
            guard++
        }
        path.reverse()
        val pts = ArrayList<DoubleArray>(path.size)
        var total = 0.0
        var pl: DoubleArray? = null
        for (p in path) {
            val la = p[0] / 1e7; val lo = p[1] / 1e7
            pts.add(doubleArrayOf(la, lo))
            if (pl != null) total += haversine(pl[0], pl[1], la, lo)
            pl = doubleArrayOf(la, lo)
        }
        return Result(pts, total, dist[t].toDouble())
    }

    private var prevArr = IntArray(0)

    private fun prevOf(i: Int): Int = if (stamp[i] == epoch) prevArr[i] else -1

    private fun heuristic(from: Int, to: Int, maxSpd: Float): Float =
        (haversine(latD(from), lngD(from), latD(to), lngD(to)) / maxSpd).toFloat()

    companion object {
        fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val phi1 = Math.toRadians(lat1); val phi2 = Math.toRadians(lat2)
            val dL = Math.toRadians(lng2 - lng1)
            val y = sin(dL) * cos(phi2)
            val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dL)
            return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
        }

        fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
            return 2 * r * asin(sqrt(a))
        }
    }
}
