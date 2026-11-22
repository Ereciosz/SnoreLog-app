package app.snorelog

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object Privacy {
    const val TEXT = "SnoreLog works on your phone only. It has no internet access and sends nothing anywhere.\n\n" +
        "Microphone: audio is recorded only while monitoring is armed and conditions are met. Clips, loudness curves, tags and statistics are stored in the app's private storage. You can delete a clip in the app, set how long clips are kept, or uninstall the app to remove everything.\n\n" +
        "Backup: a ZIP file is created only when you tap Back up, and saved where you choose. SnoreLog does not send it anywhere.\n\n" +
        "SnoreLog is not a medical device and does not diagnose sleep apnea or any other condition."
}

class PrivacyActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = L.t("Privacy") + "\n\n" + L.t(Privacy.TEXT)
            textSize = 16f; setLineSpacing(0f, 1.3f)
            setPadding((20 * d).toInt(), (56 * d).toInt(), (20 * d).toInt(), (32 * d).toInt())
            gravity = Gravity.START
        }
        setContentView(ScrollView(this).apply { addView(LinearLayout(this@PrivacyActivity).apply { addView(tv) }) })
    }
}
