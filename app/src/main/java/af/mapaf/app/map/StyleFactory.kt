package af.mapaf.app.map

import af.mapaf.app.App
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the map style from the bundled style JSONs (assets/style_light.json,
 * style_dark.json), rewiring every URL to the in-app tile server so both the
 * compact basemap (bundled in the APK, z0-12) and the installed full package
 * (z0-14) render fully offline. No external tile server is ever referenced.
 */
class StyleFactory {

    fun build(dark: Boolean): String {
        val name = if (dark) "style_dark.json" else "style_light.json"
        val text = readAsset(name)
        val style = JSONObject(text)
        val base = App.instance.tileServer.baseUrl()

        // all glyph/sprite/data references go through the local server (bundled assets)
        style.put("glyphs", "$base/fonts/{fontstack}/{range}.pbf")
        style.put("sprite", "$base/sprites/osm-liberty")

        // basemap: local PMTiles via the local tile server (works offline, both packages)
        val sources = style.getJSONObject("sources")
        val basemap = sources.getJSONObject("basemap")
        basemap.remove("url")
        basemap.put("type", "vector")
        basemap.put("tiles", JSONArray(listOf("$base/tiles/{z}/{x}/{y}.mvt")))
        basemap.put("minzoom", 0)
        // compact bundled basemap stops at z12 (MapLibre overzooms past it);
        // the installed full package carries z13-14 detail
        val fullInstalled = App.instance.settings.offlineInstalled &&
            App.instance.offlineBasemapFile().exists() &&
            App.instance.offlineBasemapFile().length() > 1_000_000
        basemap.put("maxzoom", if (fullInstalled) 14 else 12)
        basemap.put("attribution", "© OpenMapTiles · © OpenStreetMap contributors (ODbL) · دادهٔ پشتیبانی‌شده از map.af")

        // remote terrain/hillshade removed entirely — fully offline style
        sources.remove("terrain")
        style.remove("terrain")
        val layers = style.getJSONArray("layers")
        val keep = JSONArray()
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            if ("terrain" != l.optString("source")) keep.put(l)
        }
        style.put("layers", keep)
        return style.toString()
    }

    private fun readAsset(path: String): String =
        App.instance.assets.open(path).bufferedReader().use { it.readText() }
}
