package af.mapaf.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
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
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.json.JSONObject

/**
 * Main map screen — Balad-like structure: map + search bar + quick actions,
 * driven by map.af data (34 provinces, postal codes, places) in online/offline modes.
 */
class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var drawer: DrawerLayout

    private var pendingDestination: Pair<Double, Double>? = null
    private var pendingDestName: String? = null

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

        setSupportActionBar(findViewById(R.id.toolbar))
        val toggle = ActionBarDrawerToggle(this, drawer, findViewById(R.id.toolbar),
            R.string.drawer_open, R.string.drawer_close)
        drawer.addDrawerListener(toggle)
        toggle.syncState()

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

        findViewById<TextView>(R.id.search_box).setOnClickListener {
            searchLauncher.launch(Intent(this, SearchActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btn_locate).setOnClickListener { ensureLocationPermission() }
        findViewById<ImageButton>(R.id.btn_layers).setOnClickListener {
            App.instance.settings.theme = if (App.instance.settings.theme == "dark") "light" else "dark"
            loadStyle()
        }
        findViewById<ImageButton>(R.id.btn_weather).setOnClickListener { startActivity(Intent(this, WeatherActivity::class.java)) }
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // opened again from provinces/postcodes/search via CLEAR_TOP
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
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
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
        val mode = if (App.instance.settings.offlineInstalled && App.instance.offlineBasemapFile().exists())
            StyleFactory.Mode.OFFLINE else StyleFactory.Mode.ONLINE
        val json = StyleFactory().build(mode, dark)
        m.setStyle(Style.Builder().fromJson(json))
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
                st.addLayer(SymbolLayer("markers-layer", "markers").withProperties(
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
        val online = App.instance.isOnline()
        val offlineOk = App.instance.settings.offlineInstalled && App.instance.offlineBasemapFile().exists()
        val (dotRes, text) = when {
            offlineOk -> R.drawable.dot_green to getString(R.string.mode_offline)
            online -> R.drawable.dot_green to getString(R.string.mode_online)
            else -> R.drawable.dot_red to getString(R.string.mode_no_data)
        }
        statusDot.setBackgroundResource(dotRes)
        statusText.text = text
    }

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
