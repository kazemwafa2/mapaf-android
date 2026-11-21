package af.mapaf.app.ui

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import af.mapaf.app.App
import af.mapaf.app.R
import af.mapaf.app.data.DataManager

/**
 * Offline package manager — download & install the Afghanistan basemap package
 * from GitHub (like Balad's OfflineMapDownloadService). Online mode streams tiles
 * from tiles.map.af; offline mode reads the installed af.pmtiles.
 */
class OfflineManagerActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var btnAction: TextView
    private lateinit var btnCheck: TextView
    private lateinit var switchOffline: SwitchCompat
    private lateinit var repoHint: TextView
    private val manager = DataManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppLocale()
        setContentView(R.layout.activity_offline)
        statusView = findViewById(R.id.offline_status)
        progressBar = findViewById(R.id.offline_progress)
        progressText = findViewById(R.id.offline_progress_text)
        btnAction = findViewById(R.id.btn_install)
        btnCheck = findViewById(R.id.btn_check)
        switchOffline = findViewById(R.id.switch_offline)
        repoHint = findViewById(R.id.repo_hint)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        repoHint.text = App.instance.settings.dataRepoBase

        switchOffline.isChecked = App.instance.settings.offlineInstalled
        switchOffline.setOnCheckedChangeListener { _, checked ->
            App.instance.settings.offlineInstalled = checked
            App.instance.restartTileServer()
            refreshStatus()
        }

        btnCheck.setOnClickListener { checkForUpdate() }
        btnAction.setOnClickListener { install() }
        findViewById<View>(R.id.btn_remove).setOnClickListener {
            manager.remove()
            switchOffline.isChecked = false
            refreshStatus()
            Toast.makeText(this, getString(R.string.offline_removed), Toast.LENGTH_SHORT).show()
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val installed = App.instance.settings.offlineInstalled && App.instance.offlineBasemapFile().exists()
        val ver = App.instance.settings.offlineVersion
        val sizeMb = App.instance.offlineBasemapFile().length() / (1024.0 * 1024)
        statusView.text = if (installed)
            getString(R.string.offline_installed_status, ver, String.format("%.1f", sizeMb))
        else getString(R.string.offline_not_installed)
        switchOffline.isEnabled = installed
    }

    private fun checkForUpdate() {
        btnCheck.text = getString(R.string.checking)
        Thread {
            try {
                val manifest = manager.fetchManifest()
                runOnUiThread {
                    btnCheck.text = getString(R.string.check_now)
                    Toast.makeText(this,
                        getString(R.string.manifest_found, manifest.version, manifest.files.size,
                            String.format("%.0f", manifest.totalBytes / (1024.0 * 1024))),
                        Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnCheck.text = getString(R.string.check_now)
                    Toast.makeText(this, e.message ?: getString(R.string.manifest_error), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun install() {
        btnAction.isEnabled = false
        Thread {
            try {
                val manifest = manager.fetchManifest()
                manager.install(manifest) { idx, count, name, pct ->
                    runOnUiThread {
                        progressBar.max = 100
                        progressBar.progress = pct
                        progressText.text = getString(R.string.download_progress, idx + 1, count, name, pct)
                    }
                }
                runOnUiThread {
                    btnAction.isEnabled = true
                    btnAction.text = getString(R.string.install_done_btn)
                    switchOffline.isChecked = true
                    refreshStatus()
                    Toast.makeText(this, getString(R.string.offline_installed_toast), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnAction.isEnabled = true
                    Toast.makeText(this, (e.message ?: getString(R.string.install_error)), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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
