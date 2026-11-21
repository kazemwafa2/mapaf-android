package af.mapaf.app.map

import af.mapaf.app.App
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the map style from map.af's own MapLibre style JSONs (assets/style_light.json,
 * style_dark.json), rewiring every URL to the in-app tile server so both ONLINE
 * (tiles streamed from tiles.map.af/basemap/af.pmtiles over HTTP Range) and OFFLINE
 * (installed af.pmtiles package) render with map.af's exact cartography, fonts and sprites.
 */
class StyleFactory {

    enum class Mode { ONLINE, OFFLINE }

    fun build(mode: Mode, dark: Boolean): String {
        val name = if (dark) "style_dark.json" else "style_light.json"
        val text = readAsset(name)
        val style = JSONObject(text)
        val base = App.instance.tileServer.baseUrl()

        // all glyph/sprite/data references go through the local server (bundled assets)
        style.put("glyphs", "$base/fonts/{fontstack}/{range}.pbf")
        style.put("sprite", "$base/sprites/osm-liberty")

        // basemap: PMTiles via the local tile server (works for both modes)
        val sources = style.getJSONObject("sources")
        val basemap = sources.getJSONObject("basemap")
        basemap.remove("url")
        basemap.put("type", "vector")
        basemap.put("tiles", JSONArray(listOf("$base/tiles/{z}/{x}/{y}.mvt")))
        basemap.put("minzoom", 0)
        basemap.put("maxzoom", 14)
        basemap.put("attribution", "© map.af · © OpenMapTiles · © OpenStreetMap contributors (ODbL)")

        // terrain DEM is a remote-only raster — drop in offline mode
        if (mode == Mode.OFFLINE) {
            sources.remove("terrain")
            style.remove("terrain")
            val layers = style.getJSONArray("layers")
            val keep = JSONArray()
            for (i in 0 until layers.length()) {
                val l = layers.getJSONObject(i)
                if ("terrain" != l.optString("source")) keep.put(l)
            }
            style.put("layers", keep)
        } else {
            // keep terrain but normalize URL to remote tiles
            val terrain = sources.optJSONObject("terrain")
            terrain?.put("tiles", JSONArray(listOf("https://tiles.map.af/terrain/{z}/{x}/{y}.png")))
        }
        return style.toString()
    }

    private fun readAsset(path: String): String =
        App.instance.assets.open(path).bufferedReader().use { it.readText() }
}
