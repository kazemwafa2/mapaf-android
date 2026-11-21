package af.mapaf.app.data

import af.mapaf.app.App
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Weather via MET Norway Locationforecast 2.0 (compact) — the same source map.af
 * cites on its About page (معلومات آب‌وهوا: MET Norway). Free API; User-Agent required.
 */
class WeatherClient {

    data class Now(
        val tempC: Double, val windMs: Double, val windDirDeg: Double,
        val symbolCode: String, val humidity: Double, val summary: String
    )

    fun current(lat: Double, lng: Double): Now? {
        return try {
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f".format(lat, lng)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "mapaf-android/1.0 github.com/mapaf (contact: user)")
        if (conn.responseCode !in 200..299) return null
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val series = json.getJSONObject("properties").getJSONArray("timeseries")
        if (series.length() == 0) return null
        val now = series.getJSONObject(0).getJSONObject("data")
        val inst = now.getJSONObject("instant").getJSONObject("details")
        var symbol = ""
        var summary = ""
        try {
            symbol = now.getJSONObject("next_1_hours").getJSONObject("summary").getString("symbol_code")
            summary = symbol.substringBefore("_")
        } catch (_: Exception) {}
        Now(
            tempC = inst.optDouble("air_temperature", Double.NaN),
            windMs = inst.optDouble("wind_speed", Double.NaN),
            windDirDeg = inst.optDouble("wind_from_direction", Double.NaN),
            symbolCode = symbol, humidity = inst.optDouble("relative_humidity", Double.NaN),
            summary = summary
        )
        } catch (_: Exception) { null }
    }

    fun describe(appLang: String, w: Now): String {
        val desc = when {
            w.symbolCode.startsWith("rain") -> if (appLang == "ps") "باران" else if (appLang == "en") "Rain" else "باران"
            w.symbolCode.startsWith("snow") -> if (appLang == "ps") "واور" else if (appLang == "en") "Snow" else "برف"
            w.symbolCode.startsWith("cloud") || w.symbolCode.contains("partly") ->
                if (appLang == "ps") "ورېږدې" else if (appLang == "en") "Cloudy" else "ابری"
            w.symbolCode.startsWith("fair") || w.symbolCode.startsWith("clear") ->
                if (appLang == "ps") "روڼ" else if (appLang == "en") "Clear" else "صاف"
            else -> w.symbolCode.replace('_', ' ')
        }
        val dir = compass(appLang, w.windDirDeg)
        return "%.1f°C · %s · %.1f m/s %s".format(w.tempC, desc, w.windMs, dir)
    }

    private fun compass(lang: String, deg: Double): String {
        val names = if (lang == "en") arrayOf("N","NE","E","SE","S","SW","W","NW")
        else arrayOf("شمال","شمال‌شرق","شرق","جنوب‌شرق","جنوب","جنوب‌غرب","غرب","شمال‌غرب")
        if (deg.isNaN()) return ""
        val idx = (((deg + 22.5) % 360) / 45).toInt() % 8
        return names[idx]
    }
}
