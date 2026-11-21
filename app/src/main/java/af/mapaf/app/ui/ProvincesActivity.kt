package af.mapaf.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import af.mapaf.app.App
import af.mapaf.app.R
import af.mapaf.app.data.OfflineData

/** The 34 provinces of Afghanistan (map.af coverage): boundaries, capitals, postal code counts. */
class ProvincesActivity : AppCompatActivity() {

    private val data = OfflineData()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppLocale()
        setContentView(R.layout.activity_provinces)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        val list = findViewById<RecyclerView>(R.id.province_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = ProvAdapter(data.getProvinces())
    }

    class ProvAdapter(val provinces: List<OfflineData.Province>) : RecyclerView.Adapter<ProvAdapter.PVH>() {
        class PVH(val v: View) : RecyclerView.ViewHolder(v)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PVH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_province, parent, false))
        override fun getItemCount() = provinces.size
        override fun onBindViewHolder(h: PVH, pos: Int) {
            val p = provinces[pos]
            val lang = App.instance.settings.language
            h.v.findViewById<TextView>(R.id.prov_name).text = when (lang) {
                "ps" -> p.namePs; "en" -> p.nameEn; else -> p.nameFa
            }
            h.v.findViewById<TextView>(R.id.prov_capital).text = p.capitalFa
            h.v.findViewById<TextView>(R.id.prov_meta).text =
                "کد ${p.code} · ${p.postalCodesCount} کد پستی"
            h.v.setOnClickListener { v ->
                val i = Intent(v.context, MainActivity::class.java)
                    .putExtra("lat", p.capitalLat).putExtra("lng", p.capitalLng)
                    .putExtra("name", p.nameFa)
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                v.context.startActivity(i)
            }
        }
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
