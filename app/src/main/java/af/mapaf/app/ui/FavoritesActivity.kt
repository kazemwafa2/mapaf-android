package af.mapaf.app.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import af.mapaf.app.App
import af.mapaf.app.R
import af.mapaf.app.data.SearchDb

/**
 * Favorites — list of map.af registered places (bundled offline db).
 * Tapping a row returns lat/lng/name to the map as the chosen destination.
 */
class FavoritesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppLocale()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F7FA.toInt())
        }

        val header = TextView(this).apply {
            text = getString(R.string.favorites_title)
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(0xFF1976D2.toInt())
        }
        root.addView(header)

        val hint = TextView(this).apply {
            text = getString(R.string.favorites_hint)
            textSize = 12f
            setTextColor(0xFF5B7897.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(4))
        }
        root.addView(hint)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(16))
        }

        Thread {
            val rows = App.instance.searchDb.registeredAll()
            runOnUiThread { render(rows, list) }
        }.start()

        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun render(rows: List<SearchDb.Row>, list: LinearLayout) {
        if (rows.isEmpty()) {
            val t = TextView(this).apply {
                text = getString(R.string.nearby_empty)
                textSize = 14f
                setPadding(dp(12), dp(20), dp(12), dp(12))
            }
            list.addView(t)
            return
        }
        for (r in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFFFFFFFF.toInt())
                setPadding(dp(14), dp(10), dp(14), dp(10))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(6)
                layoutParams = lp
                isClickable = true
                setBackgroundResource(R.drawable.bg_chip)
            }
            val name = TextView(this).apply {
                text = r.name
                textSize = 14f
                setTextColor(0xFF1A2B3C.toInt())
                maxLines = 1
            }
            row.addView(name)
            val sub = TextView(this).apply {
                text = "${r.detail.ifEmpty { r.kind }}${if (r.nameEn.isNotEmpty()) " · ${r.nameEn}" else ""}"
                textSize = 11f
                setTextColor(0xFF5B7897.toInt())
                maxLines = 1
            }
            row.addView(sub)
            row.setOnClickListener {
                val res = android.content.Intent()
                    .putExtra("lat", r.lat).putExtra("lng", r.lng).putExtra("name", r.name)
                setResult(RESULT_OK, res)
                finish()
            }
            list.addView(row)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setAppLocale() {
        val locale = java.util.Locale(App.instance.settings.language)
        java.util.Locale.setDefault(locale)
        val cfg = android.content.res.Configuration(resources.configuration)
        cfg.setLocale(locale)
        @Suppress("DEPRECATION")
        baseContext.resources.updateConfiguration(cfg, baseContext.resources.displayMetrics)
    }
}
