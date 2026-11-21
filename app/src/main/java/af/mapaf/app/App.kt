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
        restartTileServer()
        loadGraph()
    }

    /** online = stream tiles from tiles.map.af (HTTP Range); offline = installed package */
    fun restartTileServer() {
        val offlineFile = offlineBasemapFile()
        val useOffline = settings.offlineInstalled && offlineFile.exists() && offlineFile.length() > 1000
        tileServer.start(if (useOffline) offlineFile.absolutePath else null)
    }

    fun offlineBasemapFile(): File {
        val dir = File(filesDir, "offline")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "af.pmtiles")
    }

    /** routing graph: installed package version first, then the bundled asset */
    private fun loadGraph() {
        Thread {
            val installed = File(filesDir, "offline/routing_graph.bin")
            if (installed.exists() && installed.length() > 1000 && graph.load(installed)) {
                return@Thread
            }
            try {
                val cache = File(filesDir, "routing_graph.bin")
                if (!cache.exists() || cache.length() < 1000) {
                    assets.open("graph/routing_graph.bin").use { input ->
                        cache.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                graph.load(cache)
            } catch (_: Exception) {
            }
        }.start()
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

    fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
