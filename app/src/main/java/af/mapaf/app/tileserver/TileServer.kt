package af.mapaf.app.tileserver

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal local HTTP server that feeds MapLibre — 100% OFFLINE:
 *   /tiles/{z}/{x}/{y}.mvt   — MVT tiles from the local PMTiles archive
 *                              (compact basemap bundled in the APK, or the full
 *                              package installed from the user's own GitHub release)
 *   /fonts/{stack}/{range}   — glyph PBF ranges bundled in assets
 *   /sprites/{name}          — sprite json/png bundled in assets
 *   /data/{name}             — bundled datasets (provinces.geojson, postal_codes.json…)
 *   /stats                   — tile diagnostics (for the status pill)
 *   /ping                    — health check
 *
 * No external server is contacted — the app never talks to map.af.
 */
class TileServer(private val context: Context) {

    val started = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private var port = 0

    @Volatile
    var source: PmtilesSource? = null
        private set

    /** "compact" (bundled z0-12) or "full" (installed package z0-14) */
    @Volatile
    var mode: String = "compact"
        private set

    /** invoked on the main thread once the source header + root directory are loaded */
    @Volatile
    var onSourceReady: (() -> Unit)? = null

    private val tilesOk = AtomicLong()
    private val tilesFail = AtomicLong()
    @Volatile
    var lastError: String = ""
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    /** compact = bundled basemap path; full = installed package path (may be null) */
    @Synchronized
    fun start(compactPath: String, fullPath: String?) {
        if (started.get()) stop()
        port = 8388
        var ss: ServerSocket? = null
        while (ss == null && port < 8500) {
            try { ss = ServerSocket(port) } catch (_: Exception) { port++ }
        }
        ss ?: return
        serverSocket = ss

        val useFull = fullPath != null && File(fullPath).exists() && File(fullPath).length() > 1_000_000
        mode = if (useFull) "full" else "compact"
        val src = PmtilesSource(if (useFull) fullPath!! else compactPath)
        source = src

        started.set(true)
        executor = Executors.newFixedThreadPool(10)

        // open the archive on a worker thread (never the main thread)
        Thread({
            try {
                src.open()
                mainHandler.post { onSourceReady?.invoke() }
            } catch (e: Exception) {
                lastError = "open: ${e.message ?: e.javaClass.simpleName}"
            }
        }, "TileServer-open").start()

        Thread({
            while (started.get()) {
                try {
                    val socket = ss.accept()
                    executor?.execute { handle(socket) }
                } catch (_: Exception) {
                    if (!started.get()) break
                }
            }
        }, "TileServer-accept").start()
    }

    fun baseUrl(): String = "http://127.0.0.1:$port"

    fun stats(): String {
        val ready = source?.isReady() == true
        var t = "${if (ready) "آماده" else "در حال بارگذاری"} · ✓${tilesOk.get()} ✗${tilesFail.get()}"
        if (lastError.isNotEmpty()) t += " · $lastError"
        return t
    }

    @Synchronized
    fun stop() {
        started.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        executor?.shutdownNow()
        source?.close()
        source = null
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            // headers (consume — no body expected for GET)
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = requestLine.split(" ").getOrNull(1) ?: return
            val clean = path.substringBefore('?')
            val out = BufferedOutputStream(socket.getOutputStream())
            when {
                clean.startsWith("/tiles/") -> serveTile(clean, out)
                clean.startsWith("/fonts/") -> serveAsset("fonts/" + clean.removePrefix("/fonts/").replace("%20", " ").replace(" ", "_"), "application/x-protobuf", out)
                clean.startsWith("/sprites/") -> serveAsset("sprites/" + clean.removePrefix("/sprites/"), guessSpriteType(clean), out)
                clean.startsWith("/data/") -> serveAsset("data/" + clean.removePrefix("/data/"), "application/json", out)
                clean.startsWith("/stats") -> respond(out, 200, "application/json",
                    "{\"ok\":true,\"mode\":\"$mode\",\"ready\":${source?.isReady() == true},\"tiles_ok\":${tilesOk.get()},\"tiles_fail\":${tilesFail.get()}}".toByteArray())
                clean.startsWith("/ping") -> respond(out, 200, "application/json", "{\"ok\":true}".toByteArray())
                else -> respond(out, 404, "text/plain", "not found".toByteArray())
            }
            out.flush()
        } catch (_: SocketTimeoutException) {
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun serveTile(path: String, out: java.io.OutputStream) {
        val parts = path.removePrefix("/tiles/").removeSuffix(".mvt").split("/")
        val z = parts.getOrNull(0)?.toIntOrNull()
        val x = parts.getOrNull(1)?.toIntOrNull()
        val y = parts.getOrNull(2)?.toIntOrNull()
        val s = source
        val data = if (z != null && x != null && y != null && s != null) {
            try { s.tile(z, x, y) } catch (e: Exception) {
                lastError = "${e.message ?: e.javaClass.simpleName}"
                null
            }
        } else null
        if (data == null) {
            tilesFail.incrementAndGet()
            respond(out, 404, "text/plain", "no tile".toByteArray())
            return
        }
        tilesOk.incrementAndGet()
        lastError = ""
        // PMTiles tiles are stored gzip-compressed — pass through with gzip encoding
        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/vnd.mapbox-vector-tile\r\n" +
            "Content-Encoding: gzip\r\n" +
            "Content-Length: ${data.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.ISO_8859_1))
        out.write(data)
    }

    private fun serveAsset(assetPath: String, type: String, out: java.io.OutputStream) {
        try {
            val bos = ByteArrayOutputStream()
            context.assets.open(assetPath).use { it.copyTo(bos) }
            respond(out, 200, type, bos.toByteArray())
        } catch (_: Exception) {
            respond(out, 404, "text/plain", "no asset".toByteArray())
        }
    }

    private fun guessSpriteType(path: String): String =
        if (path.endsWith(".json")) "application/json" else "image/png"

    private fun respond(out: java.io.OutputStream, code: Int, type: String, body: ByteArray) {
        val headers = "HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\n" +
            "Content-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
    }
}
