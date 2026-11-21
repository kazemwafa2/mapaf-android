package af.mapaf.app.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import af.mapaf.app.App
import af.mapaf.app.R
import af.mapaf.app.data.WeatherClient

/** Current weather for any point in Afghanistan — MET Norway Locationforecast 2.0 (map.af's cited source). */
class WeatherActivity : AppCompatActivity() {

    private val weather = WeatherClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppLocale()
        setContentView(R.layout.activity_weather)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        val lat = intent.getDoubleExtra("lat", 34.5333)
        val lng = intent.getDoubleExtra("lng", 69.1660)
        val place = intent.getStringExtra("name")

        val info = findViewById<TextView>(R.id.weather_info)
        val title = findViewById<TextView>(R.id.weather_title)
        title.text = place ?: getString(R.string.weather_default_place)
        info.text = getString(R.string.weather_loading)

        if (!App.instance.isOnline()) {
            info.text = getString(R.string.weather_offline)
            return
        }
        Thread {
            val now = weather.current(lat, lng)
            runOnUiThread {
                if (now == null) {
                    info.text = getString(R.string.weather_error)
                } else {
                    val lang = App.instance.settings.language
                    info.text = weather.describe(lang, now) + "\n\n" + getString(R.string.weather_source)
                    Toast.makeText(this, getString(R.string.weather_updated), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
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
