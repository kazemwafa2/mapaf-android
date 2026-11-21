package af.mapaf.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import af.mapaf.app.App
import af.mapaf.app.R
import af.mapaf.app.map.StyleFactory
import af.mapaf.app.nav.NavigationHolder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.json.JSONObject

/**
 * Main map screen — fully offline: compact basemap bundled in the APK (z0-12),
 * full package installable from the user's own GitHub release. Map UI follows the
 * طراحی ماکتیپ: header + search + category chips + weather + nearby sheet + bottom nav.
 */
class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var drawer: DrawerLayout
    private lateinit var nearbyRow: LinearLayout

    private var pendingDestination: Pair<Double, Double>? = null
    private var pendingDestName: String? = null
    private var styleReloadedForSource = false

    private val main = Handler(Looper.getMainLooper())
    private val statusTick = object : Runnable {
        override fun run() {
            updateStatus()
            main.postDelayed(this, 3000)
        }
    }

    private val locPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) startLocation()
    }
    private val searchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val lat = res.data?.getDoubleExtra("lat", Double.NaN) ?: Double.NaN
        val lng = res.data?.getDoubleExtra("lng", Double.NaN) ?: Double.NaN
        if (!lat.isNaN() && !lng.isNaN()) {
            val name = res.data?.getStringExtra("name")
            pendingDestination = lat to lng
            pendingDestName = name
            moveMarker(LatLng(lat, lng))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppLocale()
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.map_view)
        statusDot = findViewById(R.id.status_dot)
        statusText = findViewById(R.id.status_text)
        drawer = findViewById(R.id.drawer)
        nearbyRow = findViewById(R.id.nearby_row)

        // reload the style once the local basemap header is loaded (compact copy may lag on first run)
        App.instance.tileServer.onSourceReady = {
            if (!styleReloadedForSource) { styleReloadedForSource = true; loadStyle() }
        }

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            loadStyle()
            m.addOnMapClickListener { point ->
                pendingDestination = point.latitude to point.longitude
                pendingDestName = getString(R.string.tap_point)
                moveMarker(point)
                Toast.makeText(this, getString(R.string.tap_set_dest), Toast.LENGTH_SHORT).show()
                true
            }
            moveMarker(null)
        }

        // ---- header ----
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { drawer.openDrawer(Gravity.START) }
        findViewById<ImageButton>(R.id.btn_bell).setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
        findViewById<ImageButton>(R.id.btn_avatar).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        // ---- search & map controls ----
        findViewById<TextView>(R.id.search_box).setOnClickListener {
            searchLauncher.launch(Intent(this, SearchActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btn_locate).setOnClickListener { ensureLocationPermission() }
        findViewById<ImageButton>(R.id.btn_layers).setOnClickListener {
            App.instance.settings.theme = if (App.instance.settings.theme == "dark") "light" else "dark"
            loadStyle()
        }
        findViewById<ImageButton>(R.id.btn_zoom_in).setOnClickListener {
            map?.easeCamera(CameraUpdateFactory.zoomIn(), 220)
        }
        findViewById<ImageButton>(R.id.btn_zoom_out).setOnClickListener {
            map?.easeCamera(CameraUpdateFactory.zoomOut(), 220)
        }
        findViewById<TextView>(R.id.weather_chip).setOnClickListener { startActivity(Intent(this, WeatherActivity::class.java)) }
        findViewById<FloatingActionButton>(R.id.btn_navigate).setOnClickListener {
            val dest = pendingDestination
            if (dest == null) {
                Toast.makeText(this, getString(R.string.no_destination), Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, NavigationActivity::class.java)
                    .putExtra("lat", dest.first).putExtra("lng", dest.second)
                    .putExtra("name", pendingDestName))
            }
        }

        // ---- category chips (registered places, fully offline) ----
        findViewById<View>(R.id.chip_hotel).setOnClickListener { runNearby("Hotel", it) }
        findViewById<View>(R.id.chip_hospital).setOnClickListener { runNearby("Hospital", it) }
        findViewById<View>(R.id.chip_pharmacy).setOnClickListener { runNearby("Pharmacy", it) }
        findViewById<View>(R.id.chip_fuel).setOnClickListener { runNearby("Fuel", it) }
        findViewById<View>(R.id.chip_rest).setOnClickListener { runNearby("Restaurant", it) }
        findViewById<TextView>(R.id.nearby_title).setOnClickListener {
            findViewById<View>(R.id.nearby_scroll).visibility =
                if (findViewById<View>(R.id.nearby_scroll).visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        loadNearby(null) // general nearby on open

        // ---- bottom navigation ----
        findViewById<View>(R.id.nav_home).setOnClickListener { clearSelection() }
        findViewById<View>(R.id.nav_routes).setOnClickListener {
            val dest = pendingDestination
            if (dest == null) {
                Toast.makeText(this, getString(R.string.routes_need_dest), Toast.LENGTH_SHORT).show()
                searchLauncher.launch(Intent(this, SearchActivity::class.java))
            } else {
                startActivity(Intent(this, NavigationActivity::class.java)
                    .putExtra("lat", dest.first).putExtra("lng", dest.second)
                    .putExtra("name", pendingDestName))
            }
        }
        findViewById<View>(R.id.nav_favorites).setOnClickListener {
            searchLauncher.launch(Intent(this, FavoritesActivity::class.java))
        }
        findViewById<View>(R.id.nav_settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        // ---- drawer menu ----
        findViewById<View>(R.id.menu_provinces).setOnClickListener { startActivity(Intent(this, ProvincesActivity::class.java)) }
        findViewById<View>(R.id.menu_postcodes).setOnClickListener { startActivity(Intent(this, PostcodesActivity::class.java)) }
        findViewById<View>(R.id.menu_offline).setOnClickListener { startActivity(Intent(this, OfflineManagerActivity::class.java)) }
        findViewById<View>(R.id.menu_weather).setOnClickListener { startActivity(Intent(this, WeatherActivity::class.java)) }
        findViewById<View>(R.id.menu_settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.menu_about).setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }

        // opened from search results or provinces/postcodes screens
        if (intent.hasExtra("lat") && intent.hasExtra("lng")) {
            val lat = intent.getDoubleExtra("lat", 34.5333)
            val lng = intent.getDoubleExtra("lng", 69.166)
            pendingDestination = lat to lng
            pendingDestName = intent.getStringExtra("name")
            mapView.getMapAsync { moveMarker(LatLng(lat, lng)) }
        }
        updateStatus()
        main.postDelayed(statusTick, 3000)
        loadWeather()
    }

    private fun clearSelection() {
        pendingDestination = null
        pendingDestName = null
        moveMarker(null)
        findViewById<TextView>(R.id.search_box).text = getString(R.string.search_hint)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra("lat") && intent.hasExtra("lng")) {
            val lat = intent.getDoubleExtra("lat", 34.5333)
            val lng = intent.getDoubleExtra("lng", 69.166)
            pendingDestination = lat to lng
            pendingDestName = intent.getStringExtra("name")
            moveMarker(LatLng(lat, lng))
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        updateStatus()
    }

    fun reloadStyle() = loadStyle()

    override fun onPause() { mapView.onPause(); stopLocation(); super.onPause() }
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { mapView.onLowMemory(); super.onLowMemory() }
    override fun onDestroy() { main.removeCallbacks(statusTick); mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState)
    }

    private fun loadStyle() {
        val m = map ?: return
        val dark = when (App.instance.settings.theme) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val json = StyleFactory().build(dark)
        App.instance.tileServer.markStyleLoaded(false)
        try {
            m.setStyle(org.maplibre.android.maps.Style.Builder().fromJson(json)) { st ->
                App.instance.tileServer.markStyleLoaded(true)
                addProvinceBorders(st)
            }
        } catch (e: Exception) {
            App.instance.tileServer.markStyleLoaded(false, e.message ?: e.javaClass.simpleName)
        }
        if (pendingDestination == null && !cameraInitialized) {
            cameraInitialized = true
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(33.9, 66.9), 6.3))
        }
    }

    private var cameraInitialized = false

    /** always-visible Afghanistan 34-province borders, drawn straight from the bundled geojson */
    private fun addProvinceBorders(st: org.maplibre.android.maps.Style) {
        try {
            if (st.getSource("provinces") == null) {
                val geojson = App.instance.assets.open("data/provinces.geojson")
                    .bufferedReader().use { it.readText() }
                val src = GeoJsonSource("provinces")
                st.addSource(src)
                src.setGeoJson(geojson)
                st.addLayer(LineLayer("provinces-line", "provinces").withProperties(
                    PropertyFactory.lineColor("#4A90D9"),
                    PropertyFactory.lineWidth(1.2f),
                    PropertyFactory.lineOpacity(0.8f)
                ))
            }
        } catch (e: Exception) {
            App.instance.tileServer.markStyleLoaded(false, "provinces: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun moveMarker(point: LatLng?) {
        val m = map ?: return
        m.getStyle { st ->
            if (st.getImage("marker") == null) {
                val bmp = android.graphics.Bitmap.createBitmap(48, 64, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                p.color = 0xFFC62828.toInt()
                c.drawCircle(24f, 22f, 17f, p)
                p.color = android.graphics.Color.WHITE
                c.drawCircle(24f, 22f, 7f, p)
                val path = android.graphics.Path()
                path.moveTo(24f, 62f); path.lineTo(9f, 28f); path.lineTo(39f, 28f); path.close()
                p.color = 0xFFC62828.toInt()
                c.drawPath(path, p)
                st.addImage("marker", bmp)
            }
            if (st.getSource("markers") == null) {
                st.addSource(GeoJsonSource("markers"))
                st.addLayer(org.maplibre.android.style.layers.SymbolLayer("markers-layer", "markers").withProperties(
                    PropertyFactory.iconImage("marker"),
                    PropertyFactory.iconAllowOverlap(true)
                ))
            }
            val fc: JSONObject = if (point == null) {
                JSONObject("{\"type\":\"FeatureCollection\",\"features\":[]}")
            } else {
                JSONObject("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[${point.longitude},${point.latitude}]}}]}")
            }
            (st.getSource("markers") as? GeoJsonSource)?.setGeoJson(fc.toString())
        }
        if (point != null) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(point!!, 14.0))
            findViewById<TextView>(R.id.search_box).text = pendingDestName ?: getString(R.string.tap_point)
        }
    }

    private fun updateStatus() {
        val ts = App.instance.tileServer
        val (dotRes, text) = when {
            ts.mode == "full" -> R.drawable.dot_green to getString(R.string.mode_full) + ts.stats()
            ts.source?.isReady() == true -> R.drawable.dot_green to getString(R.string.mode_compact) + ts.stats()
            else -> R.drawable.dot_red to getString(R.string.mode_no_basemap)
        }
        statusDot.setBackgroundResource(dotRes)
        statusText.text = text
    }

    // ---- nearby places (registered places db — offline) ----
    private fun runNearby(kind: String?, chip: View? = null) {
        for (id in intArrayOf(R.id.chip_hotel, R.id.chip_hospital, R.id.chip_pharmacy, R.id.chip_fuel, R.id.chip_rest)) {
            findViewById<View>(id).setBackgroundResource(
                if (id == (chip?.id ?: -1)) R.drawable.bg_chip_sel else R.drawable.bg_chip)
            (findViewById<View>(id) as TextView).setTextColor(
                if (id == (chip?.id ?: -1)) 0xFFFFFFFF.toInt() else 0xFF1A2B3C.toInt())
        }
        loadNearby(kind)
    }

    private fun loadNearby(kind: String?) {
        Thread {
            val center = map?.cameraPosition?.target ?: LatLng(34.5333, 69.166)
            val rows = App.instance.searchDb.near(center.latitude, center.longitude, kind)
            main.post { renderNearby(rows) }
        }.start()
    }

    private fun renderNearby(rows: List<af.mapaf.app.data.SearchDb.Row>) {
        nearbyRow.removeAllViews()
        if (rows.isEmpty()) {
            val t = TextView(this)
            t.text = getString(R.string.nearby_empty)
            t.setPadding(14, 8, 14, 8)
            t.textSize = 13f
            nearbyRow.addView(t)
            return
        }
        val emojis = mapOf("Hotel" to "🏨", "Hospital" to "➕", "Pharmacy" to "💊", "Fuel" to "⛽",
            "Restaurant" to "🍴", "MedicalClinic" to "🩺", "CafeOrCoffeeShop" to "☕", "School" to "🏫")
        for (r in rows.take(10)) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setBackgroundResource(R.drawable.bg_chip)
            val lp = LinearLayout.LayoutParams(dp(124), LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(8)
            card.layoutParams = lp
            card.setPadding(dp(10), dp(8), dp(10), dp(8))
            card.isClickable = true

            val name = TextView(this)
            name.text = r.name
            name.textSize = 12f
            name.setTextColor(0xFF1A2B3C.toInt())
            name.maxLines = 1
            card.addView(name)

            val sub = TextView(this)
            val distKm = kotlin.math.sqrt(
                Math.pow(r.lat - (map?.cameraPosition?.target?.latitude ?: r.lat), 2.0) +
                Math.pow((r.lng - (map?.cameraPosition?.target?.longitude ?: r.lng)) * 0.83, 2.0))
            sub.text = "${emojis[r.kind] ?: "📍"} ${(r.detail.ifEmpty { r.nameEn.ifEmpty { r.kind } })} · %.1fkm".format(distKm)
            sub.textSize = 10f
            sub.setTextColor(0xFF5B7897.toInt())
            sub.maxLines = 1
            card.addView(sub)

            card.setOnClickListener {
                pendingDestination = r.lat to r.lng
                pendingDestName = r.name
                moveMarker(LatLng(r.lat, r.lng))
            }
            nearbyRow.addView(card)
        }
    }

    // ---- weather (MET Norway — the only online feature besides package download) ----
    private fun loadWeather() {
        Thread {
            val w = af.mapaf.app.data.WeatherClient().current(34.5333, 69.166)
            main.post {
                val chip = findViewById<TextView>(R.id.weather_chip)
                if (w != null) {
                    chip.text = "%.0f°C · %s".format(w.tempC, w.summary.ifEmpty { "کابل" })
                } else {
                    chip.text = "کابل"
                }
            }
        }.start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---- location ----
    private fun ensureLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocation()
        } else {
            locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 10f, this)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 4000L, 20f, this)
        } catch (_: Exception) {}
    }

    private fun stopLocation() {
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        } catch (_: Exception) {}
    }

    override fun onLocationChanged(location: Location) {
        NavigationHolder.position = doubleArrayOf(location.latitude, location.longitude)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(p: String?, p1: Int, p2: android.os.Bundle?) {}

    private fun setAppLocale() {
        val locale = java.util.Locale(App.instance.settings.language)
        java.util.Locale.setDefault(locale)
        val cfg = android.content.res.Configuration(resources.configuration)
        cfg.setLocale(locale)
        @Suppress("DEPRECATION")
        baseContext.resources.updateConfiguration(cfg, baseContext.resources.displayMetrics)
    }
}
