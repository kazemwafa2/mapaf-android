package af.mapaf.app.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import af.mapaf.app.App
import af.mapaf.app.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppLocale()
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        val s = App.instance.settings

        findViewById<RadioGroup>(R.id.radio_lang).apply {
            check(when (s.language) { "ps" -> R.id.lang_ps; "en" -> R.id.lang_en; else -> R.id.lang_fa })
            setOnCheckedChangeListener { _, id ->
                s.language = when (id) { R.id.lang_ps -> "ps"; R.id.lang_en -> "en"; else -> "fa" }
                Toast.makeText(context, getString(R.string.restart_hint), Toast.LENGTH_LONG).show()
            }
        }
        findViewById<RadioGroup>(R.id.radio_voice_lang).apply {
            check(when (s.voiceLanguage) { "ps" -> R.id.vlang_ps; "en" -> R.id.vlang_en; else -> R.id.vlang_fa })
            setOnCheckedChangeListener { _, id ->
                s.voiceLanguage = when (id) { R.id.vlang_ps -> "ps"; R.id.vlang_en -> "en"; else -> "fa" }
            }
        }
        findViewById<SwitchCompat>(R.id.switch_voice).apply {
            isChecked = s.voiceEnabled
            setOnCheckedChangeListener { _, c -> s.voiceEnabled = c }
        }
        findViewById<SwitchCompat>(R.id.switch_chimes).apply {
            isChecked = s.chimesEnabled
            setOnCheckedChangeListener { _, c -> s.chimesEnabled = c }
        }
        findViewById<SwitchCompat>(R.id.switch_sim).apply {
            isChecked = s.simulation
            setOnCheckedChangeListener { _, c -> s.simulation = c }
        }
        findViewById<EditText>(R.id.repo_base).setText(s.dataRepoBase)
        findViewById<View>(R.id.btn_save_repo).setOnClickListener {
            s.dataRepoBase = findViewById<EditText>(R.id.repo_base).text.toString().trim()
            Toast.makeText(this, getString(R.string.repo_saved), Toast.LENGTH_SHORT).show()
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
