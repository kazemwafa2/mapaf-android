package af.mapaf.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import af.mapaf.app.App
import af.mapaf.app.R
import af.mapaf.app.data.SearchDb
import org.json.JSONArray

/** Search: merges map.af online search (/api/search) with the bundled offline dataset. */
class SearchActivity : AppCompatActivity() {

    private lateinit var results: RecyclerView
    private lateinit var emptyView: TextView
    private val adapter = ResultAdapter { item -> submit(item) }
    private val searchDb = App.instance.searchDb
    private var latestQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppLocale()
        setContentView(R.layout.activity_search)
        results = findViewById(R.id.results)
        emptyView = findViewById(R.id.empty_view)
        results.layoutManager = LinearLayoutManager(this)
        results.adapter = adapter
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        val query = findViewById<EditText>(R.id.query)
        query.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) { runSearch(s?.toString() ?: "") }
        })
        query.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun runSearch(q: String) {
        latestQuery = q
        if (q.isBlank()) { adapter.submitList(emptyList()); emptyView.visibility = View.GONE; return }
        Thread {
            val items = ArrayList<Row>()
            // fully offline search: registered places + streets/POIs + GeoNames (no API)
            searchDb.search(q).forEach {
                items.add(Row(it.name, it.detail, it.lat, it.lng,
                    when (it.kind) { "street" -> "─"; "registered" -> "◆"; "postcode" -> "▮"; else -> "◎" },
                    online = false))
            }
            runOnUiThread {
                if (latestQuery != q) return@runOnUiThread
                adapter.submitList(items.distinctBy { it.name + it.lat + it.lng }.take(80))
                emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun submit(row: Row) {
        val intent = Intent()
            .putExtra("lat", row.lat).putExtra("lng", row.lng).putExtra("name", row.name)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    data class Row(val name: String, val detail: String, val lat: Double, val lng: Double, val icon: String, val online: Boolean)

    class ResultAdapter(private val onClick: (Row) -> Unit) : RecyclerView.Adapter<ResultAdapter.VH>() {
        private var items: List<Row> = emptyList()
        fun submitList(list: List<Row>) { items = list; notifyDataSetChanged() }
        class VH(val v: View) : RecyclerView.ViewHolder(v)
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val it = items[pos]
            h.v.findViewById<TextView>(R.id.item_icon).text = it.icon
            h.v.findViewById<TextView>(R.id.item_name).text = it.name
            h.v.findViewById<TextView>(R.id.item_detail).text =
                (if (it.online) "● " else "○ ") + it.detail
            h.v.setOnClickListener { _ -> onClick(it) }
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
