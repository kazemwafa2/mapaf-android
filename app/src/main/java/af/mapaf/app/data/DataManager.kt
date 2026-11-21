package af.mapaf.app.data

import af.mapaf.app.App
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads & installs the offline package (map + datasets) hosted on GitHub.
 * Flow (same idea as Balad's OfflineMapDownloadService):
 *   1. GET  {repoBase}/MANIFEST.json          — version, file list, sha256, sizes
 *   2. GET  each file                          — streamed with progress
 *   3. verify sha256 + activate in Settings
 */
class DataManager {

    data class FileInfo(val name: String, val size: Long, val sha256: String)
    data class Manifest(
        val version: String, val released: String, val files: List<FileInfo>,
        val totalBytes: Long, val notes: String
    )

    fun offlineDir(): File = File(App.instance.filesDir, "offline").apply { mkdirs() }

    fun basemapFile(): File = File(offlineDir(), "af.pmtiles")

    fun fetchManifest(): Manifest {
        val base = App.instance.settings.dataRepoBase
        val text = httpGet("$base/MANIFEST.json")
            ?: throw Exception("MANIFEST.json قابل دریافت نبود — اتصال یا نشانی گیت‌هاب را بررسی کنید")
        val json = JSONObject(text)
        val files = ArrayList<FileInfo>()
        val arr = json.getJSONArray("files")
        var total = 0L
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val fi = FileInfo(o.getString("name"), o.optLong("size", 0), o.optString("sha256", ""))
            files.add(fi); total += fi.size
        }
        return Manifest(
            json.optString("version", ""), json.optString("released", ""),
            files, total, json.optString("notes", "")
        )
    }

    /** downloads every file of the manifest into filesDir/offline with progress callback */
    fun install(manifest: Manifest, onProgress: (fileIdx: Int, fileCount: Int, fileName: String, filePct: Int) -> Unit) {
        val base = App.instance.settings.dataRepoBase
        val tmpDir = File(offlineDir(), "tmp").apply { mkdirs() }
        try {
            manifest.files.forEachIndexed { idx, fi ->
                val tmp = File(tmpDir, fi.name)
                val url = "$base/${fi.name}"
                downloadTo(url, tmp) { pct -> onProgress(idx, manifest.files.size, fi.name, pct) }
                val digest = sha256(tmp)
                if (fi.sha256.isNotEmpty() && !digest.equals(fi.sha256, ignoreCase = true)) {
                    throw Exception("بررسی سلامت فایل ${fi.name} ناموفق بود")
                }
                // move into place
                val target = File(offlineDir(), fi.name)
                target.delete()
                tmp.renameTo(target) ?: throw Exception("نصب ${fi.name} ناموفق بود")
            }
            App.instance.settings.offlineVersion = manifest.version
            App.instance.settings.offlineInstalled = true
            App.instance.restartTileServer()
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    fun remove() {
        offlineDir().deleteRecursively()
        offlineDir().mkdirs()
        App.instance.settings.offlineInstalled = false
        App.instance.settings.offlineVersion = ""
        App.instance.restartTileServer()
    }

    private fun downloadTo(url: String, target: File, onPct: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", "mapaf-android/1.0")
        val len = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(target).use { fos ->
                val buf = ByteArray(64 * 1024)
                var read = 0L; var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    fos.write(buf, 0, n)
                    read += n
                    if (len > 0) {
                        val pct = (read * 100 / len).toInt()
                        if (pct != lastPct) { lastPct = pct; onPct(pct) }
                    }
                }
            }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun httpGet(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "mapaf-android/1.0")
        if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
    } catch (_: Exception) { null }
}
