package af.mapaf.app.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import af.mapaf.app.App
import af.mapaf.app.R
import org.json.JSONObject

/** About screen — the official map.af statistics exactly as published on map.af/about. */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppLocale()
        setContentView(R.layout.activity_about)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        val stats = try {
            App.instance.assets.open("data/summary.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) { "{}" }
        val json = JSONObject(stats).optJSONObject("stats") ?: JSONObject()

        fun num(key: String): String = formatNumber(json.optLong(key, 0))
        findViewById<TextView>(R.id.stat_registered_places).text = num("registered_places")
        findViewById<TextView>(R.id.stat_buildings).text = num("buildings_on_map")
        findViewById<TextView>(R.id.stat_addressed).text = num("buildings_with_address")
        findViewById<TextView>(R.id.stat_streets).text = num("named_streets")
        findViewById<TextView>(R.id.stat_postcodes).text = num("postal_codes")
        findViewById<TextView>(R.id.stat_provinces).text = num("provinces")

        findViewById<TextView>(R.id.app_version).text =
            getString(R.string.app_version_line, "1.0.0", App.instance.settings.offlineVersion.ifEmpty { "—" })
    }

    /** Latin digit grouping with commas (9,884,000 — same presentation as map.af) */
    private fun formatNumber(n: Long): String = String.format("%,d", n)

    private fun setAppLocale() {
        val locale = java.util.Locale(App.instance.settings.language)
        java.util.Locale.setDefault(locale)
        val cfg = android.content.res.Configuration(resources.configuration)
        cfg.setLocale(locale)
        @Suppress("DEPRECATION")
        baseContext.resources.updateConfiguration(cfg, baseContext.resources.displayMetrics)
    }
}
