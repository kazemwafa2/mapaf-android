package af.mapaf.app.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import af.mapaf.app.R
import java.io.File

/** Shows the last crash stacktrace on screen — the user can screenshot it for debugging. */
class CrashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        val f = File(getExternalFilesDir(null), "last_crash.txt")
        tv.text = try { f.readText() } catch (_: Exception) { getString(R.string.crash_no_log) }
        tv.textSize = 13f
        tv.setPadding(24, 24, 24, 24)
        val sc = ScrollView(this)
        sc.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT)
        sc.addView(tv)
        setContentView(sc)
    }
}
