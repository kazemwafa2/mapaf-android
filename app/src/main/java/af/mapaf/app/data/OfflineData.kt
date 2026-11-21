package af.mapaf.app.data

import af.mapaf.app.App
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Offline datasets bundled in the APK (and refreshable from GitHub):
 *   data/places.json       — 32,574 populated places of Afghanistan (GeoNames, one of map.af's own sources)
 *   data/postal_codes.json — 699 Afghanistan Post 6-digit codes with coordinates (map.af/postcodes)
 *   data/provinces.geojson — boundaries of the 34 provinces (geoBoundaries)
 *   data/summary.json      — official map.af statistics for the About screen
 */
class OfflineData {

    data class Place(
        val id: String, val name: String, val nameFa: String, val nameAscii: String,
        val lat: Double, val lng: Double, val pop: Long, val type: String, val prov: String
    )

    data class Postcode(
        val code: String, val provinceFa: String, val provinceCode: String,
        val areaFa: String, val subFa: String, val lat: Double, val lng: Double
    )

    data class Province(
        val nameFa: String, val namePs: String, val nameEn: String, val code: String,
        val capitalFa: String, val capitalEn: String,
        val capitalLat: Double, val capitalLng: Double, val postalCodesCount: Int
    )

    private val places = ArrayList<Place>(33000)
    private val postcodes = ArrayList<Postcode>(720)
    private val provinces = ArrayList<Province>(34)
    @Volatile private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        val ctx = App.instance
        try {
            val placesJson = JSONObject(readAsset("data/places.json"))
            val arr = placesJson.getJSONArray("places")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                places.add(Place(
                    o.getString("id"), o.getString("name"), o.getString("name_fa"),
                    o.getString("name_ascii"), o.getDouble("lat"), o.getDouble("lng"),
                    o.optLong("pop", 0), o.getString("type"), o.optString("prov", "")
                ))
            }
        } catch (_: Exception) {}
        try {
            val pc = JSONObject(readAsset("data/postal_codes.json"))
            val arr = pc.getJSONArray("codes")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                postcodes.add(Postcode(
                    o.getString("code"), o.getString("province_fa"), o.getString("province_code"),
                    o.getString("area_fa"), o.optString("sub_fa", ""),
                    o.getDouble("lat"), o.getDouble("lng")
                ))
            }
        } catch (_: Exception) {}
        try {
            val gj = JSONObject(readAsset("data/provinces.geojson"))
            val feats = gj.getJSONArray("features")
            for (i in 0 until feats.length()) {
                val p = feats.getJSONObject(i).getJSONObject("properties")
                provinces.add(Province(
                    p.getString("name_fa"), p.getString("name_ps"), p.getString("name_en"),
                    p.optString("code", ""), p.getString("capital_fa"), p.getString("capital_en"),
                    p.getDouble("capital_lat"), p.getDouble("capital_lng"),
                    p.optInt("postal_codes_count", 0)
                ))
            }
        } catch (_: Exception) {}
        loaded = true
    }

    private fun readAsset(path: String): String =
        App.instance.assets.open(path).bufferedReader().use { it.readText() }

    private fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)
        .replace('ي', 'ی').replace('ك', 'ک')

    /** offline search: places + postal codes (prefix matches first) */
    fun search(query: String): List<SearchResult> {
        load()
        val q = normalize(query.trim())
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<SearchResult>()
        for (p in places) {
            val n1 = normalize(p.nameFa); val n2 = normalize(p.name); val n3 = normalize(p.nameAscii)
            val score = when {
                n1.startsWith(q) || n2.startsWith(q) || n3.startsWith(q) -> 0
                n1.contains(q) || n2.contains(q) || n3.contains(q) -> 1
                else -> -1
            }
            if (score >= 0) out.add(SearchResult(kind = "place", name = p.nameFa,
                nameEn = p.name, lat = p.lat, lng = p.lng, detail = p.prov,
                score = score, pop = p.pop))
        }
        for (c in postcodes) {
            val area = normalize(c.areaFa); val sub = normalize(c.subFa)
            val score = when {
                c.code.startsWith(q) -> 0
                area.startsWith(q) || sub.startsWith(q) -> 1
                area.contains(q) || sub.contains(q) -> 2
                else -> -1
            }
            if (score >= 0) out.add(SearchResult(kind = "postcode", name = c.code,
                nameEn = c.provinceFa, lat = c.lat, lng = c.lng,
                detail = "${c.provinceFa} · ${c.areaFa}${if (c.subFa.isNotEmpty()) " · ${c.subFa}" else ""}",
                score = score, pop = 0))
        }
        return out.sortedWith(compareBy({ it.score }, { -it.pop })).take(60)
    }

    fun getPlaces() = places
    fun getPostcodes() = postcodes
    fun getProvinces() = provinces

    data class SearchResult(
        val kind: String, val name: String, val nameEn: String,
        val lat: Double, val lng: Double, val detail: String, val score: Int, val pop: Long
    )
}
