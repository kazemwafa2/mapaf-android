package af.mapaf.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import af.mapaf.app.App
import af.mapaf.app.R
import af.mapaf.app.data.OfflineData

/** Afghanistan Post 6-digit codes (from map.af/postcodes): browse by province, search, open on map. */
class PostcodesActivity : AppCompatActivity() {

    private lateinit var results: RecyclerView
    private val data = OfflineData()
    private lateinit var adapter: PcAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppLocale()
        setContentView(R.layout.activity_postcodes)
        results = findViewById(R.id.pc_results)
        results.layoutManager = LinearLayoutManager(this)
        adapter = PcAdapter(data.getPostcodes())
        results.adapter = adapter
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        val provNames = data.getProvinces().map { it.nameFa }
        val chipsBox = findViewById<RecyclerView>(R.id.pc_provinces)
        chipsBox.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        chipsBox.adapter = ProvinceChips(provNames) { province ->
            adapter.filter(province, null)
        }

        val query = findViewById<EditText>(R.id.pc_query)
        query.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) { adapter.filter(null, s?.toString()) }
        })
    }

    class ProvinceChips(val names: List<String>, val onPick: (String) -> Unit) : RecyclerView.Adapter<ProvinceChips.CH>() {
        class CH(val v: View) : RecyclerView.ViewHolder(v)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_chip, parent, false))
        override fun getItemCount() = names.size
        override fun onBindViewHolder(h: CH, pos: Int) {
            (h.v as TextView).text = names[pos]
            h.v.setOnClickListener { onPick(names[pos]) }
        }
    }

    class PcAdapter(val all: List<OfflineData.Postcode>) : RecyclerView.Adapter<PcAdapter.PH>() {
        private var shown: List<OfflineData.Postcode> = all
        private var province: String? = null
        private var qq: String? = null

        fun filter(prov: String?, query: String?) {
            this.province = prov
            this.qq = query?.trim()?.takeIf { it.isNotEmpty() }
            val qv = qq
            shown = all.filter { pc ->
                (province == null || pc.provinceFa == province) &&
                    (qv == null || pc.code.startsWith(qv) || pc.areaFa.contains(qv) || pc.subFa.contains(qv) || pc.provinceFa.contains(qv))
            }
            notifyDataSetChanged()
        }

        class PH(val v: View) : RecyclerView.ViewHolder(v)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_postcode, parent, false))
        override fun getItemCount() = shown.size
        override fun onBindViewHolder(h: PH, pos: Int) {
            val pc = shown[pos]
            h.v.findViewById<TextView>(R.id.pc_code).text = pc.code
            h.v.findViewById<TextView>(R.id.pc_area).text = "${pc.provinceFa} · ${pc.areaFa}"
            h.v.findViewById<TextView>(R.id.pc_sub).text = pc.subFa
            h.v.setOnClickListener { vv ->
                val i = Intent(vv.context, MainActivity::class.java)
                    .putExtra("lat", pc.lat).putExtra("lng", pc.lng)
                    .putExtra("name", "${pc.code} · ${pc.areaFa}")
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                vv.context.startActivity(i)
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
