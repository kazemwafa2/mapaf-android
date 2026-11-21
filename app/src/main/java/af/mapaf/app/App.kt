package af.mapaf.app

import android.app.Application
import org.maplibre.android.MapLibre
import af.mapaf.app.nav.GraphRouter
import af.mapaf.app.tileserver.TileServer
import java.io.File

class App : Application() {

    lateinit var settings: Settings
        private set
    lateinit var tileServer: TileServer
        private set
    val graph = GraphRouter()
    val routeEngine by lazy { af.mapaf.app.nav.RouteEngine(graph) }
    val searchDb by lazy { af.mapaf.app.data.SearchDb() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        MapLibre.getInstance(this)
        installCrashReporter()
        settings = Settings(this)
        tileServer = TileServer(this)
        prepareBasemaps()
    }

    /** full offline package (z0-14) installed from the user's GitHub release */
    fun offlineBasemapFile(): File {
        val dir = File(filesDir, "offline")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "af.pmtiles")
    }

    /** compact basemap (z0-12) bundled in the APK — copied to filesDir on first run */
    fun compactBasemapFile(): File {
        val dir = File(filesDir, "basemap")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "af_compact.pmtiles")
    }

    /**
     * Copy the bundled compact basemap on a worker thread, then start the tile
     * server against it (or against the installed full package when present).
     * The app is 100% offline — only local files are read.
     */
    private fun prepareBasemaps() {
        Thread {
            try {
                val c = compactBasemapFile()
                if (!c.exists() || c.length() < 1_000_000) {
                    assets.open("basemap/af_compact.pmtiles").use { input ->
                        c.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            } catch (_: Exception) {
            }
            restartTileServer()
        }.start()
    }

    /** compact = bundled basemap; full = installed package (when valid) */
    fun restartTileServer() {
        val full = offlineBasemapFile()
        tileServer.start(
            compactBasemapFile().absolutePath,
            if (settings.offlineInstalled && full.exists() && full.length() > 1_000_000) full.absolutePath else null
        )
    }

    fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** write stacktrace + show it on screen so the user can screenshot the exact error */
    private fun installCrashReporter() {
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                java.io.File(getExternalFilesDir(null), "last_crash.txt").writeText(
                    android.util.Log.getStackTraceString(e))
                startActivity(android.content.Intent(this, af.mapaf.app.ui.CrashActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
                Thread.sleep(400)
            } catch (_: Exception) {
            }
            def?.uncaughtException(t, e)
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
