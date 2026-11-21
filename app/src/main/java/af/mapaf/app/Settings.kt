package af.mapaf.app

import android.content.Context
import android.content.SharedPreferences

/** Central settings (Balad-like: language, voice, theme, data source endpoints). */
class Settings(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("mapaf_settings", Context.MODE_PRIVATE)

    var language: String
        get() = sp.getString(KEY_LANG, "fa") ?: "fa"
        set(v) = sp.edit().putString(KEY_LANG, v).apply()

    var theme: String        // "light" | "dark" | "system"
        get() = sp.getString(KEY_THEME, "system") ?: "system"
        set(v) = sp.edit().putString(KEY_THEME, v).apply()

    var voiceEnabled: Boolean
        get() = sp.getBoolean(KEY_VOICE, true)
        set(v) = sp.edit().putBoolean(KEY_VOICE, v).apply()

    var chimesEnabled: Boolean
        get() = sp.getBoolean(KEY_CHIMES, true)
        set(v) = sp.edit().putBoolean(KEY_CHIMES, v).apply()

    var voiceLanguage: String     // fa | ps | en (defaults to app language)
        get() = sp.getString(KEY_VOICE_LANG, language) ?: language
        set(v) = sp.edit().putString(KEY_VOICE_LANG, v).apply()

    var simulation: Boolean       // demo navigation without GPS
        get() = sp.getBoolean(KEY_SIM, false)
        set(v) = sp.edit().putBoolean(KEY_SIM, v).apply()

    /** GitHub raw base URL hosting the offline package (user configures after upload) */
    /** GitHub release of the user's own offline-data repo (the only remote endpoint) */
    var dataRepoBase: String
        get() = sp.getString(KEY_REPO_BASE, DEFAULT_REPO_BASE) ?: DEFAULT_REPO_BASE
        set(v) = sp.edit().putString(KEY_REPO_BASE, v.trimEnd('/')).apply()

    /** installed offline package version, empty = not installed */
    var offlineVersion: String
        get() = sp.getString(KEY_OFFLINE_VER, "") ?: ""
        set(v) = sp.edit().putString(KEY_OFFLINE_VER, v).apply()

    var offlineInstalled: Boolean
        get() = sp.getBoolean(KEY_OFFLINE_ON, false)
        set(v) = sp.edit().putBoolean(KEY_OFFLINE_ON, v).apply()

    companion object {
        const val KEY_LANG = "lang"
        const val KEY_THEME = "theme"
        const val KEY_VOICE = "voice"
        const val KEY_CHIMES = "chimes"
        const val KEY_VOICE_LANG = "voice_lang"
        const val KEY_SIM = "simulation"
        const val KEY_REPO_BASE = "repo_base"
        const val KEY_OFFLINE_VER = "offline_version"
        const val KEY_OFFLINE_ON = "offline_on"
        const val DEFAULT_REPO_BASE = "https://github.com/kazemwafa2/mapaf-offline-data/releases/download/v1.0"
    }
}
