package af.mapaf.app.data

import af.mapaf.app.App
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Fully-offline search over the bundled/installed search_index.db, which merges:
 *   - map.af registered places (129,736 — scraped from the site's public pages)
 *   - every named feature of map.af's own basemap (streets, POIs, cities, peaks…)
 *   - GeoNames populated places (32,574)
 * No map.af API is used — search works with zero connectivity.
 */
class SearchDb {

    data class Row(
        val name: String, val nameEn: String, val kind: String,
        val lat: Double, val lng: Double, val detail: String, val online: Boolean = false
    )

    private var db: SQLiteDatabase? = null
    private val fallback = OfflineData()

    private fun installedFile(): File = File(App.instance.filesDir, "offline/search_index.db")

    @Synchronized
    fun open() {
        if (db != null) return
        val f = installedFile()
        if (f.exists() && f.length() > 1000) {
            db = SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            return
        }
        // copy the bundled db from assets on first use
        try {
            val target = File(App.instance.filesDir, "search_index.db")
            if (!target.exists() || target.length() < 1000) {
                App.instance.assets.open("data/search_index.db").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (target.exists() && target.length() > 1000) {
                db = SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            }
        } catch (_: Exception) {
            db = null
        }
    }

    private val kindOrder = mapOf(
        "registered" to 0, "place" to 1, "street" to 2, "poi" to 3,
        "airport" to 4, "peak" to 5, "park" to 6, "water" to 7, "river" to 8
    )

    fun search(query: String): List<Row> {
        open()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val esc = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val d = db
        if (d != null) {
            val out = ArrayList<Row>()
            try {
                val sel = "SELECT name,name_en,kind,lat,lng,detail FROM search " +
                    "WHERE name LIKE ? ESCAPE '\\' ORDER BY CASE kind " +
                    kindOrder.entries.joinToString(" ") { "WHEN '${it.key}' THEN ${it.value}" } +
                    " ELSE 9 END, LENGTH(name) LIMIT 40"
                d.rawQuery(sel, arrayOf("$esc%")).use { c ->
                    while (c.moveToNext()) {
                        out.add(Row(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "",
                            c.getDouble(3), c.getDouble(4), c.getString(5) ?: ""))
                    }
                }
                if (out.size < 40) {
                    val sel2 = "SELECT name,name_en,kind,lat,lng,detail FROM search " +
                        "WHERE name LIKE ? ESCAPE '\\' AND name NOT LIKE ? ESCAPE '\\' " +
                        "ORDER BY CASE kind " +
                        kindOrder.entries.joinToString(" ") { "WHEN '${it.key}' THEN ${it.value}" } +
                        " ELSE 9 END LIMIT ${40 - out.size}"
                    d.rawQuery(sel2, arrayOf("%$esc%", "$esc%")).use { c ->
                        while (c.moveToNext()) {
                            out.add(Row(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "",
                                c.getDouble(3), c.getDouble(4), c.getString(5) ?: ""))
                        }
                    }
                }
                if (out.isNotEmpty()) return out
            } catch (_: Exception) {
                // fall through to bundled fallback
            }
        }
        return fallback.search(q).map {
            Row(it.name, it.nameEn, it.kind, it.lat, it.lng, it.detail)
        }
    }

    fun close() {
        try { db?.close() } catch (_: Exception) {}
        db = null
    }
}
