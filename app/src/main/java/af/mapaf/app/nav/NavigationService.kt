package af.mapaf.app.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import af.mapaf.app.App
import af.mapaf.app.R
import af.mapaf.app.voice.VoiceEngine

/**
 * Foreground navigation service (Balad has an equivalent NavigationService):
 * receives location updates (or advances a simulation), tracks progress along
 * the route and triggers the bundled voice prompts.
 */
class NavigationService : Service(), LocationListener {

    private lateinit var voice: VoiceEngine
    private val handler = Handler(Looper.getMainLooper())
    private var route: RouteEngine.Route? = null
    private var simIndex = 0
    private var simStep = 0
    private var lastVoiceManeuverIdx = -1
    private var lastVoiceDistanceKey = ""
    private var announcedStart = false
    private var announcedApproach = false
    private var announcedArrival = false
    private var offRouteSince = 0L
    private var simRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        voice = VoiceEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(""))
        route = NavigationHolder.route
        if (route == null) { stopSelf(); return START_NOT_STICKY }
        voice.playPrompts(listOf("start"))
        announcedStart = true
        if (App.instance.settings.simulation) startSimulation() else requestLocation()
        return START_STICKY
    }

    private fun requestLocation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 5f, this, Looper.getMainLooper())
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 10f, this, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {}
    }

    private fun stopLocation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try { lm.removeUpdates(this) } catch (_: Exception) {}
    }

    // ---- simulation (demo mode — walks along the route) ----
    private fun startSimulation() {
        simIndex = 0; simStep = 0
        simRunnable = object : Runnable {
            override fun run() {
                advanceSimulation()
                handler.postDelayed(this, 900)
            }
        }
        handler.post(simRunnable!!)
    }

    private fun advanceSimulation() {
        val r = route ?: return
        if (simIndex >= r.points.size - 1) {
            onProgress(r, r.points.last()[0], r.points.last()[1])
            return
        }
        simStep++
        if (simStep >= SIM_STEPS_PER_SEG) { simStep = 0; simIndex++ }
        val a = r.points[simIndex.coerceAtMost(r.points.size - 2)]
        val b = r.points[(simIndex + 1).coerceAtMost(r.points.size - 1)]
        val f = simStep.toDouble() / SIM_STEPS_PER_SEG
        val lat = a[0] + (b[0] - a[0]) * f
        val lng = a[1] + (b[1] - a[1]) * f
        onProgress(r, lat, lng)
        NavigationHolder.position = doubleArrayOf(lat, lng)
    }

    // ---- progress + voice ----
    private fun onProgress(r: RouteEngine.Route, lat: Double, lng: Double) {
        val eng = App.instance.routeEngine
        val (distToRoute, nearestIdx) = eng.distanceToRoute(r, lat, lng)
        val remaining = eng.remainingMeters(r, nearestIdx, lat, lng)
        val next = eng.nextManeuver(r, nearestIdx, lat, lng)

        NavigationHolder.remainingMeters = remaining
        NavigationHolder.nextManeuver = next

        // arrival handling
        if (remaining < 25.0) {
            if (!announcedArrival) {
                announcedArrival = true
                voice.playChime(VoiceEngine.CHIME_ARRIVAL)
                voice.playPrompts(listOf("arrival"))
                if (!App.instance.settings.simulation) stopNavigation()
                return
            }
            return
        }
        if (remaining < 120.0 && !announcedApproach) {
            announcedApproach = true
            voice.playPrompts(listOf("approaching"))
        }

        // off-route detection (GPS mode only)
        if (!App.instance.settings.simulation && distToRoute > 60.0) {
            if (offRouteSince == 0L) offRouteSince = System.currentTimeMillis()
            else if (System.currentTimeMillis() - offRouteSince > 12000) {
                offRouteSince = 0L
                voice.playChime(VoiceEngine.CHIME_REROUTE)
                voice.playPrompts(listOf("offroute"))
                NavigationHolder.requestReroute = true
            }
        } else offRouteSince = 0L

        // turn-by-turn voice: distance bucket + maneuver, once per maneuver
        if (next != null && next.pointIdx != lastVoiceManeuverIdx && next.distanceMeters > 0) {
            val bucket = voice.distanceKey(next.distanceMeters)
            if (bucket != lastVoiceDistanceKey || next.pointIdx != lastVoiceManeuverIdx) {
                if (next.pointIdx != lastVoiceManeuverIdx) {
                    val dKey = if (next.distanceMeters <= 50) "" else voice.distanceKey(next.distanceMeters)
                    voice.playChime(VoiceEngine.CHIME_TURN)
                    val prompts = if (dKey.isEmpty()) listOf(next.promptKey)
                                  else listOf(dKey, next.promptKey)
                    voice.playPrompts(prompts)
                    lastVoiceManeuverIdx = next.pointIdx
                    lastVoiceDistanceKey = bucket
                }
            }
        }
    }

    // ---- LocationListener ----
    override fun onLocationChanged(location: Location) {
        val r = route ?: return
        NavigationHolder.position = doubleArrayOf(location.latitude, location.longitude)
        NavigationHolder.speedMs = location.speed.toDouble()
        onProgress(r, location.latitude, location.longitude)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(p: String?, p1: Int, p2: android.os.Bundle?) {}

    fun stopNavigation() {
        handler.post { stopSelf() }
    }

    override fun onDestroy() {
        stopLocation()
        simRunnable?.let { handler.removeCallbacks(it) }
        voice.release()
        NavigationHolder.active = false
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("nav", getString(R.string.notif_channel_nav), NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, "nav")
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text.ifEmpty { getString(R.string.notif_navigating) })
            .setSmallIcon(R.drawable.ic_nav)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 42
        const val SIM_STEPS_PER_SEG = 6
    }
}

/** shared state between NavigationActivity and NavigationService */
object NavigationHolder {
    var route: RouteEngine.Route? = null
    var position: DoubleArray? = null
    var speedMs: Double = 0.0
    var remainingMeters: Double = 0.0
    var nextManeuver: RouteEngine.Maneuver? = null
    var requestReroute: Boolean = false
    var active: Boolean = false
    var destination: Pair<Double, Double>? = null
}
