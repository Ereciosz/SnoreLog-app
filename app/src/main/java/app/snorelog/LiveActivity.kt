package app.snorelog

import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import com.google.android.material.R as M

/** Test mode: live level meter with the same trigger logic as the recorder, nothing is saved. */
class LiveActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var stop = false
    private var th: Thread? = null
    private lateinit var big: TextView
    private lateinit var verdict: TextView
    private lateinit var info: TextView
    private lateinit var graph: LevelView
    @Volatile private var resetFloor = false

    override fun onCreate(b: Bundle?) {
        val st = Settings(this)
        AppCompatDelegate.setDefaultNightMode(when (st.themeMode) { 1 -> AppCompatDelegate.MODE_NIGHT_NO; 2 -> AppCompatDelegate.MODE_NIGHT_YES; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM })
        if (st.dynamic && DynamicColors.isDynamicColorAvailable()) DynamicColors.applyToActivityIfAvailable(this)
        else theme.applyStyle(listOf(R.style.AccentOrange, R.style.AccentTeal, R.style.AccentViolet, R.style.AccentGreen)[st.accent.coerceIn(0, 3)], true)
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val sub = MaterialColors.getColor(this, M.attr.colorOnSurfaceVariant, Color.GRAY)
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        fun tv(t: String, style: Int, c: Int? = null) = TextView(this).apply { text = t; setTextAppearance(style); if (c != null) setTextColor(c) }

        big = tv("-- dBFS", M.style.TextAppearance_Material3_DisplaySmall)
        verdict = tv("Starting microphone...", M.style.TextAppearance_Material3_TitleMedium)
        info = tv("", M.style.TextAppearance_Material3_BodyMedium).apply { setLineSpacing(0f, 1.3f) }
        graph = LevelView(this).apply { seconds = false }
        val hint = tv("Make a snoring sound, or play a snoring video next to the phone. Bars above the dashed line would start an event. Nothing is recorded or saved here.", M.style.TextAppearance_Material3_LabelMedium, sub)
        val reset = MaterialButton(this, null, M.attr.materialButtonOutlinedStyle).apply { text = "Reset noise floor"; setOnClickListener { resetFloor = true } }
        val close = MaterialButton(this).apply { text = "Close"; setOnClickListener { finish() } }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(tv("Test mode", M.style.TextAppearance_Material3_HeadlineSmall))
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(big, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            addView(verdict, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(graph, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(info, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(reset, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(close, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val root = ScrollView(this).apply { addView(content) }
        setContentView(root)
        L.walk(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, i ->
            val s = i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, s.top, 0, s.bottom); i
        }
    }

    // The whole UI is built in code and refreshed from the database, so view state is never restored.
    // Restoring it crashed (ClassCastException in CompoundButton) when the system recreated the activity.
    override fun onRestoreInstanceState(savedInstanceState: Bundle) { }

    override fun onStart() { super.onStart(); stop = false; th = Thread { run() }.also { it.start() } }
    override fun onStop() { super.onStop(); stop = true; th?.join(1500); th = null }

    private fun run() {
        val st = Settings(this)
        val sr = Cfg.SAMPLE_RATE; val frame = sr / 10
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try { AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, frame * 8)) } catch (t: Throwable) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            h.post { verdict.text = L.t("Microphone unavailable (permission?)") }; return
        }
        val yam = Yamnet(this).takeIf { it.ok }
        val buf = ShortArray(frame)
        val win = ShortArray(15600)           // last ~1 s for the classifier
        val tmp = ShortArray(15600)
        var wp = 0; var wfill = 0
        val hist = FloatArray(300); var hn = 0
        var floor = Double.NaN; var frames = 0; var ai = -1f
        var accMax = -120.0; var accZcr = 0.0; var accN = 0; var accLoud = 0; var accHigh = 0
        rec.startRecording()
        try {
            while (!stop) {
                val r = rec.read(buf, 0, frame)
                if (r < 0) break
                if (r == 0) continue
                frames++
                var s = 0.0; var zc = 0
                for (i in 0 until r) {
                    val v = buf[i].toDouble(); s += v * v
                    if (i > 0 && (buf[i] >= 0) != (buf[i - 1] >= 0)) zc++
                    win[wp] = buf[i]; wp = (wp + 1) % win.size
                }
                wfill = min(win.size, wfill + r)
                val d = 20 * log10(sqrt(s / r) / 32768.0 + 1e-9)
                val zcr = zc.toDouble() / r
                if (resetFloor || floor.isNaN()) { floor = d; resetFloor = false }
                val thr = floor + st.margin
                val loud = d > thr && zcr < Cfg.MAX_ZCR
                if (!loud) floor += (d - floor) * 0.02
                if (yam != null && frames % 10 == 0 && wfill >= win.size) {
                    for (i in tmp.indices) tmp[i] = win[(wp + i) % win.size]
                    ai = yam.snoreProb(tmp, tmp.size)
                }
                if (hn == hist.size) { System.arraycopy(hist, 1, hist, 0, hist.size - 1); hn-- }
                hist[hn++] = d.toFloat()
                accMax = max(accMax, d); accZcr += zcr; accN++
                if (loud) accLoud++ else if (d > thr) accHigh++
                if (frames % 2 == 0) {
                    val snap = hist.copyOf(hn); val f = floor
                    h.post { graph.levels = snap; graph.thr = (f + st.margin).toFloat(); graph.invalidate() }
                }
                // texts change twice a second at most, from the last 0.5 s, so they are readable
                if (frames % 5 == 0) {
                    val f = floor; val a = ai
                    val dMax = accMax; val zcrAvg = accZcr / accN
                    val verdictText = when {
                        accLoud == 0 && accHigh == 0 -> "Below trigger - ignored"
                        accLoud == 0 -> "Loud but high-pitched - ignored"
                        else -> "Would trigger"
                    }
                    accMax = -120.0; accZcr = 0.0; accN = 0; accLoud = 0; accHigh = 0
                    h.post {
                        big.text = "%.0f dBFS".format(Locale.US, dMax)
                        verdict.text = L.t(verdictText)
                        info.text = L.t("Noise floor %.0f dBFS, trigger %.0f dBFS (+%d dB)\n".format(Locale.US, f, f + st.margin, st.margin) +
                            "Zero crossings: %.2f (limit %.2f)\n".format(Locale.US, zcrAvg, Cfg.MAX_ZCR) +
                            (if (yam == null) "AI classifier: not in this build" else
                                if (a < 0) "AI snoring score: listening..." else "AI snoring score: %.2f (keeps at %.2f or more)".format(Locale.US, a, st.aiPct / 100f)))
                    }
                }
            }
        } finally {
            try { rec.stop() } catch (t: Throwable) { }
            rec.release(); yam?.close()
        }
    }
}
