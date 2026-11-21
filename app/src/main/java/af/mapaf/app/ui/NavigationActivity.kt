package af.mapaf.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import af.mapaf.app.App
import af.mapaf.app.R
import af.mapaf.app.map.StyleFactory
import af.mapaf.app.nav.NavigationHolder
import af.mapaf.app.nav.NavigationService
import af.mapaf.app.nav.RouteEngine
import af.mapaf.app.voice.VoiceEngine
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.json.JSONObject

/** Turn-by-turn navigation screen with the in-app Dari/Pashto/English voice guidance. */
class NavigationActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private lateinit var bannerText: TextView
    private lateinit var etaText: TextView
    private lateinit var nextText: TextView
    private lateinit var speedText: TextView
    private lateinit var approxNote: TextView

    private val routeEngine = App.instance.routeEngine
    private var route: RouteEngine.Route? = null
    private var dest: Pair<Double, Double>? = null
    private var destName: String? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateHud()
            handler.postDelayed(this, 1000)
        }
    }

    private val locPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) startService(Intent(this, NavigationService::class.java))
        else Toast.makeText(this, getString(R.string.need_location_perm), Toast.LENGTH_LONG).show()
    }

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { bound = true }
        override fun onServiceDisconnected(name: ComponentName?) { bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppLocale()
        setContentView(R.layout.activity_navigation)

        dest = intent.getDoubleExtra("lat", Double.NaN).let { lat ->
            val lng = intent.getDoubleExtra("lng", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) null else lat to lng
        }
        destName = intent.getStringExtra("name")

        mapView = findViewById(R.id.nav_map)
        bannerText = findViewById(R.id.banner_next)
        etaText = findViewById(R.id.banner_eta)
        nextText = findViewById(R.id.next_info)
        speedText = findViewById(R.id.speed_view)
        approxNote = findViewById(R.id.approx_note)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isLogoEnabled = false
            val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            m.setStyle(Style.Builder().fromJson(StyleFactory().build(dark))) {
                prepareRouteLayers(it)
                calculateRoute { r -> drawRoute(r) }
            }
        }

        findViewById<ImageButton>(R.id.btn_stop).setOnClickListener {
            stopService(Intent(this, NavigationService::class.java))
            finish()
        }
        findViewById<ImageButton>(R.id.btn_sound).setOnClickListener { v ->
            App.instance.settings.voiceEnabled = !App.instance.settings.voiceEnabled
            v.alpha = if (App.instance.settings.voiceEnabled) 1f else 0.4f
            Toast.makeText(this,
                getString(if (App.instance.settings.voiceEnabled) R.string.sound_on else R.string.sound_off),
                Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.btn_sim).setOnClickListener {
            App.instance.settings.simulation = !App.instance.settings.simulation
            Toast.makeText(this, getString(
                if (App.instance.settings.simulation) R.string.sim_on else R.string.sim_off), Toast.LENGTH_SHORT).show()
        }
    }

    private fun prepareRouteLayers(st: Style) {
        if (st.getSource("route") == null) st.addSource(GeoJsonSource("route"))
        if (st.getLayer("route-casing") == null) st.addLayer(
            LineLayer("route-casing", "route").withProperties(
                PropertyFactory.lineColor("#1565C0"), PropertyFactory.lineWidth(11f),
                PropertyFactory.lineOpacity(0.9f), PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round")
            ))
        if (st.getLayer("route-line") == null) st.addLayer(
            LineLayer("route-line", "route").withProperties(
                PropertyFactory.lineColor("#2196F3"), PropertyFactory.lineWidth(7f),
                PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round")
            ))
    }

    private fun calculateRoute(onRoute: (RouteEngine.Route) -> Unit) {
        val d = dest ?: return
        Thread {
            val origin = NavigationHolder.position?.let { it[0] to it[1] } ?: (34.5333 to 69.166)
            val r = routeEngine.route(origin.first, origin.second, d.first, d.second)
                ?: routeEngine.straightLine(origin.first, origin.second, d.first, d.second)
            runOnUiThread {
                route = r
                approxNote.visibility = if (r.approximate) View.VISIBLE else View.GONE
                NavigationHolder.route = r
                NavigationHolder.active = true
                onRoute(r)
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun drawRoute(r: RouteEngine.Route) {
        val st = map?.style ?: return
        val coords = StringBuilder()
        r.points.forEachIndexed { i, p ->
            if (i > 0) coords.append(",")
            coords.append("[${p[1]},${p[0]}]")
        }
        val routeSrc = st.getSource("route") as? GeoJsonSource
        routeSrc?.setGeoJson("{\"type\":\"LineString\",\"coordinates\":[$coords]}")
        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(
            LatLngBounds.Builder().also { b -> r.points.forEach { b.include(LatLng(it[0], it[1])) } }.build(),
            120))
        // start the foreground service (real GPS or simulation) + bind for state
        startNavService()
        handler.post(ticker)
    }

    private fun startNavService() {
        androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, NavigationService::class.java))
    }

    private fun updateHud() {
        val r = route ?: return
        val rem = NavigationHolder.remainingMeters
        val next = NavigationHolder.nextManeuver
        bannerText.text = next?.let { "${it.text}  ${formatDistance(it.distanceMeters)}" } ?: formatDistance(rem.toInt())
        val mins = Math.ceil(rem / 13.9 / 60.0).toInt() // ~50 km/h average
        val eta = java.lang.String.format(java.util.Locale.US, "%d:%02d", mins / 60, mins % 60)
        etaText.text = getString(R.string.eta_format, formatDistance(rem.toInt()), eta)
        nextText.text = destName ?: getString(R.string.nav_active)
        val speed = NavigationHolder.speedMs * 3.6
        speedText.text = java.lang.String.format(java.util.Locale.US, "%.0f", speed)
    }

    private fun formatDistance(m: Int): String {
        val lang = App.instance.settings.language
        return if (m >= 1000) {
            if (lang == "en") String.format("%.1f km", m / 1000.0)
            else String.format("%.1f کیلومتر", m / 1000.0)
        } else {
            if (lang == "en") "$m m" else "$m متر"
        }
    }

    override fun onResume() { super.onResume(); mapView.onResume(); handler.post(ticker) }
    override fun onPause() { mapView.onPause(); handler.removeCallbacks(ticker); super.onPause() }
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { mapView.onLowMemory(); super.onLowMemory() }
    override fun onDestroy() {
        mapView.onDestroy()
        if (bound) unbindService(serviceConn)
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState)
    }

    private fun setAppLocale() {
        val locale = java.util.Locale(App.instance.settings.language)
        java.util.Locale.setDefault(locale)
        val cfg = android.content.res.Configuration(resources.configuration)
        cfg.setLocale(locale)
        @Suppress("DEPRECATION")
        baseContext.resources.updateConfiguration(cfg, baseContext.resources.displayMetrics)
    }
}
