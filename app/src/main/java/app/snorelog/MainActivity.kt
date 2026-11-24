package app.snorelog

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import android.widget.HorizontalScrollView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min
import com.google.android.material.R as M

class MainActivity : AppCompatActivity() {
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val BODY = M.style.TextAppearance_Material3_BodyMedium
    private val TITLE = M.style.TextAppearance_Material3_TitleMedium
    private val LABEL = M.style.TextAppearance_Material3_LabelMedium

    private val TAGS = listOf("Alcohol", "Congestion", "Late meal", "Back sleeping", "Stress/tired")
    private val THEMES = listOf("System", "Light", "Dark", "OLED")
    private val ACCENTS = listOf("Orange", "Teal", "Violet", "Green")
    private val chips = LinkedHashMap<String, Chip>()
    private val pages = ArrayList<View>()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var toggleBtn: MaterialButton
    private lateinit var navTitle: TextView
    private lateinit var statsTv: TextView
    private lateinit var pauseBox: LinearLayout
    private lateinit var pauseStats: LinearLayout
    private lateinit var pauseChips: LinearLayout
    private var pauseClr = 0
    private lateinit var newTv: TextView
    private lateinit var newCard: View
    private lateinit var loudBtn: MaterialButton
    private var loudClip: Ev? = null
    private lateinit var monthLabel: TextView
    private lateinit var insights: TextView
    private lateinit var batteryTv: TextView
    private lateinit var bannerCard: MaterialCardView
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var bannerText: TextView
    private lateinit var playerCard: MaterialCardView
    private lateinit var miniBar: LinearLayout
    private var queue: List<Ev> = emptyList()
    private val openGroups = HashSet<Long>()
    private lateinit var aiLabel: TextView
    private lateinit var playerTitle: TextView
    private lateinit var playBtn: MaterialButton
    private lateinit var seek: Slider
    private lateinit var timeTv: TextView
    private lateinit var infoTv: TextView
    private lateinit var level: LevelView
    private lateinit var rejCard: MaterialCardView
    private var rootView: View? = null
    private lateinit var evHead: TextView
    private lateinit var rejHead: TextView
    private lateinit var rejBody: LinearLayout
    private var evOpen = true
    private var rejOpen = false
    private var evCount = 0
    private var rejCount = 0
    private lateinit var rejList: LinearLayout
    private lateinit var storageTv: TextView
    private val backupOut = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) doBackup(uri) }
    private val backupIn = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) doRestore(uri) }
    private lateinit var ring: ScoreRing
    private var lastScore: Score.Result? = null
    private val showScoreInfo = Runnable {
        val r = lastScore ?: return@Runnable
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(L.f("Score %d", r.score))
            .setMessage(
                L.f("Snoring: %.1f min of %.0f min recorded (%.0f%%): -%d points", r.snoreMin, r.recMin, r.pct, r.snorePenalty) + "\n" +
                L.f("Loudness: 95th percentile peak %.0f dBFS: -%d points", r.p95, r.loudPenalty) + "\n\n" +
                L.t("Score = 100 - 1.5 x snoring % - loudness penalty (up to 15). A 95th percentile peak of -40 dBFS or quieter costs nothing, -10 dBFS or louder costs 15."))
            .setPositiveButton(L.t("Close"), null).show()
    }
    private lateinit var tl: TimelineView
    private lateinit var bars: BarChartView
    private lateinit var cal: CalendarView
    private lateinit var list: LinearLayout
    private lateinit var bottomNav: BottomNavigationView

    private var selected = 0
    private var subOpen = -1
    private var tab = 0
    private lateinit var settingsHome: LinearLayout
    private var subPages: List<View> = emptyList()
    private var subTitles: List<String> = emptyList()
    private var subSummaries: () -> Unit = {}
    private val backCb = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { openSub(-1) }
    }
    private var player: MediaPlayer? = null
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null
    private fun applyBoost(db: Int) {
        try { enhancer?.setTargetGain(db * 100) } catch (_: Throwable) {}
    }
    private var current: Ev? = null
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 3000) } }
    private val playTick = object : Runnable {
        override fun run() {
            val mp = player
            if (mp != null && mp.isPlaying) {
                seek.value = mp.currentPosition.toFloat().coerceIn(0f, seek.valueTo)
                timeTv.text = "${fmtMs(mp.currentPosition)} / ${fmtMs(mp.duration)}"
                if (mp.duration > 0) { level.cursor = mp.currentPosition.toFloat() / mp.duration; level.invalidate() }
            }
            h.postDelayed(this, 250)
        }
    }
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val hmFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fmtMs(ms: Int) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
    private fun clr(attr: Int) = MaterialColors.getColor(this, attr, Color.GRAY)
    private fun toast(t: String) = Toast.makeText(this, L.t(t), Toast.LENGTH_SHORT).show()
    private fun tv(t: String, style: Int, color: Int? = null) = TextView(this).apply {
        text = t; setTextAppearance(style); if (color != null) setTextColor(color)
    }
    private fun sec(t: String) = tv(t.uppercase(), LABEL, clr(M.attr.colorOnSurfaceVariant)).apply { setPadding(0, 0, 0, dp(8)) }
    private fun col(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; v.forEach { addView(it) } }
    private fun card(vararg v: View) = MaterialCardView(this, null, M.attr.materialCardViewOutlinedStyle).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        if (Look.contrast) { strokeWidth = dp(2); strokeColor = clr(M.attr.colorOutline) }
        addView(col(*v).apply { setPadding(dp(16), dp(14), dp(16), dp(14)) })
    }
    private fun button(t: String, outlined: Boolean = false, onClick: () -> Unit): MaterialButton {
        val b = if (outlined) MaterialButton(this, null, M.attr.materialButtonOutlinedStyle) else MaterialButton(this)
        b.text = t; b.setOnClickListener { onClick() }
        return b
    }
    private fun slider(lo: Float, hi: Float, init: Int, onChange: (Int) -> Unit) = Slider(this).apply {
        valueFrom = lo; valueTo = hi; stepSize = 1f; value = init.toFloat()
        addOnChangeListener { _, v, _ -> onChange(v.toInt()) }
    }
    private fun switch(t: String, on: Boolean, onChange: (Boolean) -> Unit) = MaterialSwitch(this).apply {
        text = t; isChecked = on; setOnCheckedChangeListener { _, v -> onChange(v) }
        setPadding(0, dp(6), 0, dp(6))
    }
    /** A switch whose sub-settings are shown only while it is on. */
    private fun gated(t: String, on: Boolean, onChange: (Boolean) -> Unit, vararg inner: View): View {
        val box = col(*inner).apply { visibility = if (on) View.VISIBLE else View.GONE }
        return col(switch(t, on) { onChange(it); box.visibility = if (it) View.VISIBLE else View.GONE }, box)
    }
    /** A collapsed section opened by an outlined button. */
    private fun fold(title: String, vararg inner: View): View {
        val box = col(*inner).apply { visibility = View.GONE }
        return col(button(title, true) { box.visibility = if (box.visibility == View.VISIBLE) View.GONE else View.VISIBLE }, box)
    }
    /** Keeps a button label on one line by shrinking it (10-14 sp) instead of cutting it with "...". */
    private fun fitText(b: MaterialButton) {
        b.maxLines = 1
        b.setPadding(dp(6), b.paddingTop, dp(6), b.paddingBottom)
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(b, 10, 14, 1, TypedValue.COMPLEX_UNIT_SP)
    }
    private fun toggleGroup(labels: List<String>, sel: Int, onSel: (Int) -> Unit): MaterialButtonToggleGroup {
        val g = MaterialButtonToggleGroup(this)
        g.isSingleSelection = true; g.isSelectionRequired = true
        val ids = labels.map { View.generateViewId() }
        labels.forEachIndexed { i, l ->
            val b = MaterialButton(this, null, M.attr.materialButtonOutlinedStyle)
            b.id = ids[i]; b.text = l
            fitText(b)
            g.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        g.check(ids[sel.coerceIn(0, labels.size - 1)])
        g.addOnButtonCheckedListener { _, id, checked -> if (checked) onSel(ids.indexOf(id)) }
        return g
    }

    override fun onCreate(b: Bundle?) {
        val st = Settings(this)
        Look.apply(this, st)
        super.onCreate(b)
        selected = Night.key(System.currentTimeMillis())
        val sub = clr(M.attr.colorOnSurfaceVariant)

        toolbar = MaterialToolbar(this).apply { title = "SnoreLog"; setNavigationOnClickListener { openSub(-1) } }
        onBackPressedDispatcher.addCallback(this, backCb)

        // ---- Night page
        bannerText = tv("", BODY)
        bannerCard = card(sec("Monitoring stopped"), bannerText,
            button("Dismiss", true) { st.armed = false; refresh() }).apply { visibility = View.GONE }
        newTv = tv("", LABEL)
        newCard = card(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(newTv, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(button("\u2715", true) { newCard.visibility = View.GONE }.apply { minWidth = 0; minimumWidth = 0 })
        }).apply { visibility = View.GONE }
        navTitle = tv("", TITLE).apply { gravity = Gravity.CENTER }
        val nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0)
            addView(button("<", true) { shift(-1) })
            addView(navTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(button(">", true) { shift(1) })
        }
        ring = ScoreRing(this)
        ring.setOnClickListener {
            ring.removeCallbacks(showScoreInfo)
            if (!Egg.tap(this)) ring.postDelayed(showScoreInfo, 350)
        }
        loudBtn = button("", true) { loudClip?.let { load(it) } }.apply { visibility = View.GONE }
        statsTv = tv("", BODY).apply { setLineSpacing(0f, 1.3f) }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ring)
            addView(statsTv, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(16) })
        }
        tl = TimelineView(this).apply { onPick = { e -> load(e) } }
        pauseClr = Color.parseColor(if (!Look.night) (if (Look.contrast) "#006064" else "#00838F") else "#26C6DA")
        pauseStats = LinearLayout(this).apply { setPadding(0, dp(4), 0, dp(8)) }
        pauseChips = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        pauseBox = col(
            tv(L.t("Suspected pauses (>=10 s)").uppercase(), LABEL, pauseClr).apply { setPadding(0, dp(16), 0, 0) },
            pauseStats,
            HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pauseChips) },
            tv("Tap a time to zoom the timeline to it.", LABEL, clr(M.attr.colorOnSurfaceVariant)).apply { setPadding(0, dp(4), 0, 0) }
        ).apply { visibility = View.GONE }
        val selColor = clr(M.attr.colorSecondaryContainer)
        val chipGroup = ChipGroup(this)
        TAGS.forEach { t ->
            val chip = Chip(this).apply {
                text = t; isCheckable = true
                chipBackgroundColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(selColor, Color.TRANSPARENT))
                setOnClickListener { toggleTag(t) }
            }
            chips[t] = chip; chipGroup.addView(chip)
        }
        playerTitle = tv("", TITLE)
        infoTv = tv("", LABEL, sub).apply { setPadding(0, dp(4), 0, dp(8)) }
        level = LevelView(this).apply { onSeek = { f -> player?.let { mp -> mp.seekTo((f * mp.duration).toInt()); cursor = f; invalidate() } } }
        playBtn = button("Play") { togglePlay() }
        seek = Slider(this).apply {
            valueFrom = 0f; valueTo = 1f; value = 0f
            addOnChangeListener { _, v, fromUser -> if (fromUser) player?.seekTo(v.toInt()) }
        }
        timeTv = tv("0:00 / 0:00", BODY, sub)
        val clipActions = LinearLayout(this).apply {
            addView(button("Share", true) { current?.let { shareClip(it) } }.also { fitText(it) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
            addView(button("Save to Downloads", true) { current?.let { saveClip(it) } }.also { fitText(it) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        val delBtn = button("Delete clip", true) { current?.let { confirmDelete(listOf(it)) } }
        playerCard = card(sec("Player"), playerTitle, infoTv, level, clipActions, delBtn).apply { visibility = View.GONE }
        miniBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(4)); visibility = View.GONE
            setBackgroundColor(clr(M.attr.colorSurfaceContainer))
            addView(playBtn, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(seek, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
            addView(timeTv, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(button("\u2715", true) { closePlayer() }.apply { minWidth = 0; minimumWidth = 0; contentDescription = L.t("Close player") },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rejList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rejHead = tv("", TITLE, clr(M.attr.colorPrimary)).apply { setPadding(0, dp(4), 0, dp(4)); setOnClickListener { rejOpen = !rejOpen; heads() } }
        rejBody = col(tv("Clips the filters dropped. If real snoring is here, lower the thresholds or switch a filter off.", LABEL, sub), rejList)
        rejCard = card(rejHead, rejBody).apply { visibility = View.GONE }
        evHead = tv("", TITLE, clr(M.attr.colorPrimary)).apply { setPadding(0, dp(4), 0, dp(4)); setOnClickListener { evOpen = !evOpen; heads() } }
        statusTitle = tv("", TITLE)
        statusText = tv("", BODY, sub)
        statusCard = MaterialCardView(this, null, M.attr.materialCardViewFilledStyle).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
            addView(col(statusTitle, statusText).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) })
        }
        val nightPage = col(newCard, statusCard, bannerCard, nav, card(top, loudBtn), card(sec("Timeline"), tl, tv("Bar height is the share of time spent snoring. Pinch to zoom, drag to pan, tap a bar to play it, double-tap to switch between the events and the whole night.", LABEL, clr(M.attr.colorOnSurfaceVariant)), pauseBox),
            card(sec("Tags for this night"), chipGroup), playerCard, card(evHead, list), rejCard)

        // ---- Trends page
        bars = BarChartView(this).apply { onPick = { this@MainActivity.selected = it; cal.month = Night.cal(it); refresh() } }
        cal = CalendarView(this).apply {
            month = Night.cal(this@MainActivity.selected)
            onPick = { this@MainActivity.selected = it; bottomNav.selectedItemId = 0 }
        }
        monthLabel = tv("", TITLE).apply { gravity = Gravity.CENTER }
        val monthNav = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(button("<", true) { cal.month.add(Calendar.MONTH, -1); refresh() })
            addView(monthLabel, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(button(">", true) { cal.month.add(Calendar.MONTH, 1); refresh() })
        }
        insights = tv("", BODY).apply { setLineSpacing(0f, 1.3f) }
        val trendsPage = col(card(sec("Last 14 nights - minutes of snoring"), bars),
            card(sec("Calendar"), monthNav, cal), card(sec("Tag insights"), insights))

        // ---- Settings page
        val marginLabel = tv("", BODY)
        fun mText(v: Int) { marginLabel.text = "Trigger: room noise + $v dB (lower = more sensitive)" }
        mText(st.margin)
        val alarmLabel = tv("", BODY).apply { setPadding(0, dp(12), 0, 0) }
        fun lText(v: Int) { alarmLabel.text = "I am up when the screen stays on for $v min" }
        lText(max(st.alarmLimit, 15))
        val heatLabel = tv("", BODY).apply { setPadding(0, dp(8), 0, 0) }
        fun hText(v: Int) { heatLabel.text = "Pause at battery temperature $v \u00B0C (resumes at ${v - 4} \u00B0C)" }
        hText(st.heatLimit)
        aiLabel = tv("", BODY)
        fun iText(v: Int) { aiLabel.text = "AI score needed: ${"%.2f".format(Locale.US, v / 100.0)}" }
        iText(st.aiPct)
        val floorLabel = tv("", BODY).apply { setPadding(0, dp(8), 0, 0) }
        fun fText(v: Int) { floorLabel.text = "Stop recording below $v% battery" }
        fText(st.batteryFloor)
        val delayLabel = tv("", BODY).apply { setPadding(0, dp(8), 0, 0) }
        fun dText(v: Int) { delayLabel.text = if (v == 0) "Start immediately when the screen turns off" else "Start $v min after the screen turns off" }
        dText(st.screenDelay)
        val pi = try { packageManager.getPackageInfo(packageName, 0) } catch (e: Exception) { null }
        val versionName = pi?.versionName ?: "?"
        val aboutText = "Version $versionName (build ${if (pi != null) androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi) else 0})\n" +
            (if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) "Debug build\n" else "") +
            "Package $packageName\n" +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            (try { assets.open("yamnet.tflite").close(); "AI classifier model: included" } catch (e: Exception) { "AI classifier model: not in this build" })
        val modelStatus = try { assets.open("yamnet.tflite").close(); "Model: included in this build." } catch (e: Exception) { "Model: not in this build (filter is skipped)." }
        batteryTv = tv("", LABEL, sub).apply { setPadding(0, dp(8), 0, dp(8)) }
        storageTv = tv("", BODY).apply { setPadding(0, 0, 0, dp(8)) }
        val boostLabel = tv("", BODY)
        fun bText(v: Int) { boostLabel.text = "Playback boost: +$v dB" }
        bText(st.boostDb)
        val retLabel = tv("", BODY)
        fun rText(v: Int) { retLabel.text = "Delete clips and events after $v days" }
        rText(st.retention)
        val disclaimer = tv("Pause detection is not a medical device. It marks silences of 10-90 s between loud breathing. If you or a partner notice choking or gasping, or you are very sleepy by day, see a doctor.", BODY, sub)
        val alarmSl = slider(5f, 60f, st.alarmLimit) { st.alarmLimit = it; lText(it) }
        fun page(vararg v: View) = col(*v).apply { visibility = View.GONE }
        val subPages = listOf(
            page(card(
                tv("Language", BODY),
                toggleGroup(listOf("System", "English", "Polski"), st.lang) { st.lang = it; L.mode = it; recreate() },
                tv("Theme", BODY).apply { setPadding(0, dp(12), 0, 0) },
                toggleGroup(THEMES, st.themeMode) { st.themeMode = it; AppCompatDelegate.setDefaultNightMode(Look.delegateMode(it)); recreate() },
                tv("OLED: pure black background, saves power on OLED screens.", LABEL, sub).apply { setPadding(0, dp(4), 0, 0) },
                switch("High contrast", st.contrast) { st.contrast = it; recreate() }.apply { setPadding(0, dp(12), 0, dp(2)) },
                tv("Pure black or white background, bold outlines and maximum text contrast. Replaces dynamic colours.", LABEL, sub),
                switch("Dynamic colours (Android 12+)", st.dynamic) { st.dynamic = it; recreate() }.apply { isEnabled = !st.contrast },
                tv("Accent colour (used when dynamic colours are off)", BODY).apply { setPadding(0, dp(8), 0, dp(4)) },
                toggleGroup(ACCENTS, st.accent) { st.accent = it; recreate() })),
            page(card(
                gated("Disarm when I get up", st.alarmOn, { st.alarmOn = it },
                    tv("Counts from your next Clock alarm (within 14 h of arming). A snooze keeps it armed. With no alarm set, it starts counting once the screen has been off for 10 min (fixed, separate from the recording delay below).", LABEL, sub),
                    alarmLabel, alarmSl,
                    tv("A ringing alarm keeps the screen on, so use 15 min or more.", LABEL, sub)),
                tv("When I unplug the phone", BODY).apply { setPadding(0, dp(12), 0, dp(4)) },
                toggleGroup(listOf("Pause", "After alarm", "Always"), st.unplugMode) { st.unplugMode = it },
                tv("Pause: recording resumes when you plug in again. After alarm: unplugging after your alarm disarms (needs Disarm when I get up). Always: any unplug disarms.", LABEL, sub),
                gated("Record only while the screen is off", st.screenOffOnly, { st.screenOffOnly = it },
                    tv("Pauses while the screen is on (YouTube, reading) and resumes after the delay below once it turns off.", LABEL, sub),
                    delayLabel, slider(0f, 10f, st.screenDelay) { st.screenDelay = it; dText(it) }),
                fold("Advanced",
                    gated("Also record on battery", st.batteryMode, { st.batteryMode = it },
                        tv("Experimental. Lets it record without a charger. Costs battery (my estimate: a few percent per night, not yet measured on your phone) and the system is more likely to stop the app. It stops at the limit below and resumes when you plug in.", LABEL, sub),
                        floorLabel, slider(10f, 60f, st.batteryFloor) { st.batteryFloor = it; fText(it) }),
                    tv("Battery mode keeps recording after an unplug until the battery floor.", LABEL, sub)))),
            page(card(
                button("Test mode (live meter)", true) { startActivity(Intent(this, LiveActivity::class.java)) },
                tv("Shows loudness, trigger level and AI score live, so you can tune sensitivity by day.", LABEL, sub),
                aiLabel.apply { setPadding(0, dp(12), 0, 0) }, slider(5f, 90f, st.aiPct) { st.aiPct = it; iText(it) },
                tv("Keeps an event only if its AI snoring score is at or above this. Higher drops more noise (and maybe some real snoring). Applies to new recordings; old clips keep their saved score.", LABEL, sub),
                boostLabel.apply { setPadding(0, dp(12), 0, 0) }, slider(0f, 30f, st.boostDb) { st.boostDb = it; bText(it); applyBoost(it) },
                tv("Playback boost: makes clips louder when played, so the phone volume does not have to be at maximum. Does not change the saved files. Very high values may distort loud snores.", LABEL, sub),
                fold("Advanced",
                    marginLabel.apply { setPadding(0, dp(12), 0, 0) }, slider(4f, 24f, st.margin) { st.margin = it; mText(it) },
                    switch("Detect farts (for fun)", st.fart) { st.fart = it },
                    tv("Experimental. When the AI hears a fart more strongly than snoring, the clip is counted as a fart instead of snoring. Only sounds of 0.8 s or longer are checked. Fart clips are kept for the normal retention time.", LABEL, sub),
                    switch("Vibrate after 15 s of continuous snoring", st.nudge) { st.nudge = it },
                    switch("Suspected breathing-pause detection (experimental)", st.apnea) { st.apnea = it },
                    tv("Changes apply at the next start.", LABEL, sub).apply { setPadding(0, dp(8), 0, 0) },
                    tv("A small on-device model (YAMNet) checks each event for snoring and drops the rest. Uses a bit more battery.\n$modelStatus", LABEL, sub)))),
            page(card(
                gated("Pause recording when the phone is hot", st.heatGuard, { st.heatGuard = it },
                    heatLabel, slider(36f, 50f, st.heatLimit) { st.heatLimit = it; hText(it) },
                    tv("Also pauses when Android reports severe overheating. SnoreLog itself adds little heat, so a hot phone usually means the charger, a case or bedding. You get a notification, and recording resumes by itself after it cools.", LABEL, sub))),
                card(
                button("Notification sounds and channels", true) { openNotificationSettings() },
                tv("Each kind of message has its own channel (stopped by the system, phone too hot, auto-disarm), all silent by default. Open this to give any of them a sound or pop-up.", LABEL, sub),
                switch("Watchdog alert", st.watchdog) { st.watchdog = it },
                tv("Every ~15 min checks that monitoring is alive. If the system killed it, you get a loud notification to arm again.", LABEL, sub),
                batteryTv,
                button("Allow background activity", true) { requestIgnoreBattery() },
                button("Open app battery settings", true) { openAppSettings() }),
                card(
                button("Allow display over other apps", true) { openOverlay() },
                tv("Optional. Some phones then treat the app as more important and are less likely to stop it.", LABEL, sub),
                button("Bedside mode (screen stays on, black)", true) { startActivity(Intent(this, BedsideActivity::class.java)) },
                tv("Optional, strongest protection: keeps the app visible all night with a black, dimmed screen. Uses a little battery on OLED. Long-press the screen to exit.", LABEL, sub)),
                card(tv("The persistent notification keeps the service in the foreground, but the phone's battery manager can still stop it. Set Battery to Unrestricted for this app. After a reboot, arm again.", LABEL, sub))),
            page(card(storageTv,
                retLabel, slider(1f, 30f, st.retention) { st.retention = it; rText(it) },
                switch("Keep rejected clips for review (3 days)", st.keepRej) { st.keepRej = it },
                button("Clean up now", true) { val d = Db(this); d.purge(st.retention, 3); d.close(); refresh(); toast("Done") },
                button("Delete rejected clips", true) { val d = Db(this); d.deleteRejected(); d.close(); refresh(); toast("Deleted") }),
                card(
                    tv("Backup", TITLE),
                    tv("Saves all events, clips and settings into one ZIP file that you choose where to keep (Files, Drive, a computer). Restoring replaces everything on this phone with the backup. Disarm first.", LABEL, sub),
                    button("Back up to ZIP", true) { backupOut.launch("SnoreLog_backup_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".zip") },
                    button("Restore from ZIP", true) { confirmRestore() },
                    button("Export CSV", true) { export() })),
            page(card(
                tv("SnoreLog", TITLE),
                tv(aboutText, BODY, sub).apply { setLineSpacing(0f, 1.3f); setPadding(0, dp(4), 0, 0) },
                button("What's new", true) { changelogDialog() },
                button("Healthy sleep habits (website)", true) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sleepeducation.org/healthy-sleep/healthy-sleep-habits/"))) } catch (_: Exception) { toast("Not available on this phone") }
                },
                button("View event log", true) { showLog() },
                button("Share event log", true) { shareLog() },
                tv("The log records service start/stop, errors, battery and heat, to find out why monitoring stops.", LABEL, sub),
                tv("Made with Claude by Erecik \u2665", BODY).apply {
                    setPadding(0, dp(12), 0, 0); gravity = Gravity.CENTER
                    setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai"))) } catch (_: Exception) {} }
                }),
                card(
                tv("Privacy", TITLE),
                tv("Audio is recorded and analysed on this phone only. SnoreLog has no internet access and sends nothing anywhere. Clips and statistics stay in the app's own storage until you delete them or they expire.", BODY, sub).apply { setLineSpacing(0f, 1.3f) },
                button("Full privacy text", true) { startActivity(Intent(this, PrivacyActivity::class.java)) }),
                card(
                tv("Not a medical device", TITLE),
                disclaimer),
                card(
                tv("Open-source components", TITLE),
                tv("Material Components for Android, AndroidX, TensorFlow Lite and the YAMNet audio model (Google) are used under the Apache License 2.0.", BODY, sub).apply { setLineSpacing(0f, 1.3f) }))
        )
        subPages.forEach { it.visibility = View.GONE }
        val subTitles = listOf("Appearance", "Schedule", "Detection", "Reliability", "Storage & data", "About")
        val subSums = subTitles.map { tv("", LABEL, sub) }
        subSummaries = {
            val on = { b: Boolean -> L.t(if (b) "on" else "off") }
            subSums[0].text = L.f("%s theme, dynamic colours %s", L.t(THEMES[st.themeMode.coerceIn(0, THEMES.size - 1)]), on(st.dynamic && !st.contrast)) + (if (st.contrast) ", " + L.t("high contrast") else "")
            subSums[1].text = L.f("Alarm disarm %s, screen-off only %s", on(st.alarmOn), on(st.screenOffOnly))
            subSums[2].text = L.f("Trigger +%d dB, AI score %.2f", st.margin, st.aiPct / 100.0)
            subSums[3].text = L.f("Watchdog %s, bedside mode, battery settings", on(st.watchdog))
            subSums[4].text = L.f("Keep %d days, rejected clips %s", st.retention, on(st.keepRej))
            subSums[5].text = L.f("Version %s, privacy, log, licences", versionName)
        }
        subSummaries()
        settingsHome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            subTitles.forEachIndexed { i, t ->
                addView(MaterialCardView(this@MainActivity, null, M.attr.materialCardViewOutlinedStyle).apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
                    isClickable = true; isFocusable = true
                    setOnClickListener { openSub(i) }
                    addView(LinearLayout(this@MainActivity).apply {
                        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
                        addView(col(tv(t, TITLE), subSums[i]), LinearLayout.LayoutParams(0, WRAP, 1f))
                        addView(tv(">", TITLE, clr(M.attr.colorPrimary)))
                    })
                })
            }
        }
        this.subPages = subPages; this.subTitles = subTitles
        val settingsPage = col(settingsHome, *subPages.toTypedArray())

        pages.addAll(listOf(nightPage, trendsPage, settingsPage))
        val content = col(nightPage, trendsPage, settingsPage).apply { setPadding(dp(16), dp(4), dp(16), dp(16)) }
        val scroll = ScrollView(this).apply { addView(content); isVerticalScrollBarEnabled = false }

        toggleBtn = button("Arm monitoring") { onToggle() }.apply { minHeight = dp(56); cornerRadius = dp(28) }
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(clr(M.attr.colorSurfaceContainer))
            addView(View(this@MainActivity).apply { setBackgroundColor(clr(M.attr.colorOutlineVariant)) }, LinearLayout.LayoutParams(MATCH, dp(1)))
            addView(LinearLayout(this@MainActivity).apply {
                setPadding(dp(16), dp(10), dp(16), dp(10))
                addView(toggleBtn, LinearLayout.LayoutParams(MATCH, WRAP))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        toggleBtn.strokeWidth = dp(2); toggleBtn.strokeColor = ColorStateList.valueOf(clr(M.attr.colorPrimary))

        bottomNav = BottomNavigationView(this).apply {
            menu.add(Menu.NONE, 0, Menu.NONE, L.t("Night")).setIcon(R.drawable.ic_night)
            menu.add(Menu.NONE, 1, Menu.NONE, L.t("Trends")).setIcon(R.drawable.ic_trends)
            menu.add(Menu.NONE, 2, Menu.NONE, L.t("Settings")).setIcon(R.drawable.ic_settings)
            setOnItemSelectedListener { showTab(it.itemId); true }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(miniBar, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(actionBar)
            addView(bottomNav, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        setContentView(root)
        rootView = root
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, i ->
            val s = i.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, s.top, 0, s.bottom); i
        }
        showTab(0)
        Diag.log(this, "app opened; last process exit: " + exitInfo().trim())
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        else h.postDelayed({ startupDialogs() }, 1500)
    }

    // The whole UI is built in code and refreshed from the database, so view state is never restored.
    // Restoring it crashed (ClassCastException in CompoundButton) when the system recreated the activity.
    override fun onRestoreInstanceState(savedInstanceState: Bundle) { }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        h.postDelayed({ startupDialogs() }, 500)
    }

    private var startupDone = false
    /** First launch: battery/background advice. Runs once per process. */
    private fun startupDialogs() {
        if (startupDone) return
        startupDone = true
        val st = Settings(this)
        val code = try { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0)).toInt() } catch (e: Exception) { 0 }
        val upgraded = st.battIntro && st.lastVer != code   // not a fresh install
        st.lastVer = code
        val rest = {
            if (!st.battIntro && !ignoringBattery()) { st.battIntro = true; batteryDialog() }
            else st.battIntro = true
        }
        if (upgraded) { newTv.text = L.t(Changelog.latest()); newCard.visibility = View.VISIBLE; L.walk(newCard) }
        rest()
    }

    private fun changelogDialog(after: (() -> Unit)? = null) {
        val t = TextView(this).apply { text = L.t("What's new") + "\n\n" + Changelog.text(); setPadding(dp(24), dp(8), dp(24), dp(8)); textSize = 15f; setLineSpacing(0f, 1.2f) }
        val sv = android.widget.ScrollView(this).apply { addView(t) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(L.t("What's new"))
            .setView(sv.also { t.text = Changelog.text() })
            .setPositiveButton("OK", null)
            .setOnDismissListener { after?.invoke() }
            .show()
    }

    private fun ignoringBattery() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun batteryDialog(after: (() -> Unit)? = null) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(L.t("Keep monitoring alive"))
            .setMessage(L.t("To record all night, Android must not stop SnoreLog in the background. Tap Allow, then in the app's battery settings (Settings > Reliability) choose Unrestricted. Without it the phone may end monitoring during the night."))
            .setPositiveButton(L.t("Allow")) { _, _ -> requestIgnoreBattery() }
            .setNeutralButton(L.t("Don't remind me")) { _, _ -> Settings(this).battNever = true }
            .setNegativeButton(L.t("Not now"), null)
            .setOnDismissListener { after?.invoke() }
            .show()
    }

    override fun onResume() { super.onResume(); tick.run(); playTick.run() }
    override fun onPause() {
        super.onPause()
        h.removeCallbacks(tick); h.removeCallbacks(playTick)
        player?.takeIf { it.isPlaying }?.pause()
        playBtn.text = "Play"
    }
    override fun onDestroy() { super.onDestroy(); enhancer?.release(); enhancer = null; player?.release(); player = null }

    private fun showTab(i: Int) {
        tab = i
        if (::miniBar.isInitialized) miniBar.visibility = if (i == 0 && player != null) View.VISIBLE else View.GONE
        pages.forEachIndexed { j, p -> p.visibility = if (j == i) View.VISIBLE else View.GONE }
        if (i != 2) subOpen = -1
        openSub(subOpen)
        refresh()
    }

    private fun openSub(i: Int) {
        subOpen = i
        settingsHome.visibility = if (i < 0) View.VISIBLE else View.GONE
        subPages.forEachIndexed { j, p -> p.visibility = if (j == i) View.VISIBLE else View.GONE }
        if (i < 0) subSummaries()
        toolbar.title = if (i >= 0 && tab == 2) L.t(subTitles[i]) else "SnoreLog"
        if (i >= 0 && tab == 2) toolbar.setNavigationIcon(R.drawable.ic_back) else toolbar.navigationIcon = null
        backCb.isEnabled = i >= 0 && tab == 2
        L.walk(rootView)
    }

    private fun shift(d: Int) {
        val c = Night.cal(selected); c.add(Calendar.DAY_OF_MONTH, d)
        val k = Night.keyOf(c)
        if (k <= Night.key(System.currentTimeMillis())) { selected = k; cal.month = Night.cal(k); refresh() }
    }

    private fun toggleTag(t: String) {
        val d = Db(this); val cur = d.allTags()[selected] ?: emptySet()
        d.setTags(selected, if (t in cur) cur - t else cur + t); d.close(); refresh()
    }

    private fun onToggle() {
        if (RecorderService.running) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Microphone permission required")
        } else {
            ContextCompat.startForegroundService(this, Intent(this, RecorderService::class.java))
            if (!ignoringBattery() && !Settings(this).battNever) h.postDelayed({ batteryDialog() }, 600)
        }
        h.postDelayed({ refresh() }, 400)
    }

    // ---- player
    private fun load(e: Ev, q: List<Ev> = listOf(e)) {
        queue = q
        if (!File(e.path).exists()) { toast("Clip file is missing"); return }
        enhancer?.release(); enhancer = null
        player?.release(); player = null
        val mp = MediaPlayer()
        try { mp.setDataSource(e.path); mp.prepare() } catch (x: Exception) { mp.release(); toast("Cannot play this clip"); return }
        mp.setOnCompletionListener {
            val i = queue.indexOfFirst { it.id == e.id }
            if (i >= 0 && i + 1 < queue.size) load(queue[i + 1], queue) else { playBtn.text = "Play"; seek.value = 0f }
        }
        try { enhancer = android.media.audiofx.LoudnessEnhancer(mp.audioSessionId).also { it.setTargetGain(Settings(this).boostDb * 100); it.enabled = true } } catch (_: Throwable) { enhancer = null }
        player = mp; current = e; tl.selId = e.id; tl.invalidate()
        playerCard.visibility = View.VISIBLE
        miniBar.visibility = if (tab == 0) View.VISIBLE else View.GONE
        playerTitle.text = timeFmt.format(Date(e.ts)) + "  -  " + "%.1f".format(e.durMs / 1000.0) + " s" + (if (e.rej) "  (rejected)" else "")
        infoTv.text = (if (e.rej) "${L.t("Rejected")}: ${L.t(e.reason)}. " else "") + scoreText(e).let { if (it.isEmpty()) "" else "$it  |  " } +
            L.t("trigger %.0f dBFS, peak %.0f dBFS".format(Locale.US, e.thr, e.peak))
        val d = Db(this); level.levels = d.levels(e.id); d.close()
        level.thr = if (e.thr != 0.0) e.thr.toFloat() else Float.NaN
        level.cursor = 0f; level.invalidate()
        seek.value = 0f
        seek.valueTo = max(mp.duration, 1).toFloat()
        timeTv.text = "0:00 / ${fmtMs(mp.duration)}"
        mp.start(); playBtn.text = "Pause"
    }

    private fun heads() {
        evHead.text = "EVENTS ($evCount)   " + if (evOpen) "\u25B4" else "\u25BE"
        rejHead.text = "REJECTED BY FILTERS ($rejCount)   " + if (rejOpen) "\u25B4" else "\u25BE"
        list.visibility = if (evOpen) View.VISIBLE else View.GONE
        rejBody.visibility = if (rejOpen) View.VISIBLE else View.GONE
        L.walk(rootView)
    }

    private fun confirmDelete(es: List<Ev>) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(L.t(if (es.size == 1) "Delete this clip?" else "Delete ${es.size} merged clips?"))
            .setMessage(L.t("The recording is removed from the phone and cannot be recovered."))
            .setNegativeButton(L.t("Cancel"), null)
            .setPositiveButton(L.t("Delete")) { _, _ ->
                val ids = es.map { it.id }.toSet()
                if (current?.id in ids) {
                    player?.release(); player = null; current = null
                    playerCard.visibility = View.GONE; miniBar.visibility = View.GONE
                }
                val d = Db(this); d.deleteEvents(ids.toList()); d.close()
                refresh()
            }.show()
    }

    private fun scoreText(e: Ev): String {
        val p = ArrayList<String>()
        if (e.cls >= 0) p.add("AI %.2f".format(Locale.US, e.cls))
        return p.joinToString(", ")
    }

    private fun closePlayer() {
        enhancer?.release(); enhancer = null
        player?.release(); player = null; current = null
        playBtn.text = "Play"; seek.value = 0f
        tl.selId = -1L; tl.invalidate()
        playerCard.visibility = View.GONE; miniBar.visibility = View.GONE
    }

    private fun togglePlay() {
        val mp = player ?: return
        if (mp.isPlaying) { mp.pause(); playBtn.text = "Play" } else { mp.start(); playBtn.text = "Pause" }
    }

    private fun shareClip(e: Ev) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", File(e.path))
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), L.t("Share clip")))
        } catch (x: Exception) { toast("Cannot share this clip") }
    }

    private fun saveClip(e: Ev) {
        try {
            val name = "snorelog_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(e.ts)) + ".m4a"
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "audio/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/SnoreLog")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (uri == null) { toast("Cannot save"); return }
            contentResolver.openOutputStream(uri)?.use { out -> File(e.path).inputStream().use { it.copyTo(out) } }
            toast("Saved to Downloads/SnoreLog")
        } catch (x: Exception) { toast("Cannot save this clip") }
    }

    // ---- battery
    private fun requestIgnoreBattery() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (x: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (y: Exception) { toast("Not available on this phone") }
        }
    }

    private fun openNotificationSettings() {
        try {
            Chan.create(this)
            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (x: Exception) { toast("Not available on this phone") }
    }

    private fun openOverlay() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (x: Exception) { toast("Not available on this phone") }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (x: Exception) { toast("Not available on this phone") }
    }

    private fun refresh() {
        val tz = java.util.TimeZone.getDefault(); timeFmt.timeZone = tz; hmFmt.timeZone = tz
        val st = Settings(this)
        val run = RecorderService.running
        val rec = RecorderService.recording
        toolbar.subtitle = if (rec) "Recording" else if (run) "Armed" else "Idle"
        statusTitle.text = if (rec) "Recording" else if (run) "Armed - not recording yet" else "Not armed"
        statusText.text = if (rec) "Listening while charging." else if (run)
            RecorderService.detail.ifEmpty { "Waiting for charger" } else "Tap Arm monitoring, then connect the charger."
        toggleBtn.text = if (run) "Disarm" else "Arm monitoring"
        toggleBtn.backgroundTintList = ColorStateList.valueOf(clr(if (run) M.attr.colorSecondaryContainer else M.attr.colorPrimary))
        toggleBtn.setTextColor(clr(if (run) M.attr.colorOnSecondaryContainer else M.attr.colorOnPrimary))
        batteryTv.text = if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            "Battery optimisation: off for this app (good)" else "Battery optimisation: on (the system may stop monitoring)"

        val db = Db(this)
        val all = db.all(); val tagsMap = db.allTags(); val pauseAll = db.pauses(); val sess = db.sessions(); val rejAll = db.rejected()
        db.close()
        fun mb(l: List<Ev>) = "%.1f MB".format(Locale.US, l.sumOf { File(it.path).length() } / 1e6)
        storageTv.text = "Kept: ${all.size} clips, ${mb(all)}\nRejected: ${rejAll.size} clips, ${mb(rejAll)}"
        if (st.armed && !run) {
            val last = sess.maxOfOrNull { it.second }
            bannerText.text = "The system probably ended monitoring" + (if (last != null) " (last activity ${hmFmt.format(Date(last))})" else "") +
                exitInfo() + "\nSet Battery to Unrestricted in Settings > Reliability, then arm again."
            bannerCard.visibility = View.VISIBLE
        } else bannerCard.visibility = View.GONE

        val byNight = all.groupBy { Night.key(it.ts) }
        val mins = byNight.mapValues { en -> en.value.sumOf { it.durMs } / 60000.0 }

        cal.data = mins; cal.selected = selected; cal.invalidate()
        monthLabel.text = SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(cal.month.time)
        val today = Night.cal(Night.key(System.currentTimeMillis()))
        bars.nights = (13 downTo 0).map { i ->
            val c = today.clone() as Calendar; c.add(Calendar.DAY_OF_MONTH, -i)
            val k = Night.keyOf(c); k to (mins[k] ?: 0.0)
        }
        bars.selected = selected; bars.invalidate()

        navTitle.text = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Night.cal(selected).time) +
            (if (selected == Night.keyOf(today)) "  (latest)" else "")
        val ev = (byNight[selected] ?: emptyList()).sortedBy { it.ts }
        tl.events = ev; tl.key = selected

        if (ev.isEmpty()) {
            ring.score = -1
            loudClip = null; loudBtn.visibility = View.GONE
            statsTv.text = "No snoring events\nrecorded this night"
        } else {
            val m = ev.sumOf { it.durMs } / 60000.0
            val peak = ev.maxOf { it.peak }
            val sc = Score.calc(ev, sess.filter { Night.key(it.first) == selected }.sumOf { it.second - it.first } / 60000.0)
            val score = sc.score
            lastScore = sc
            val others = bars.nights.takeLast(7).filter { it.first != selected }.map { it.second }
            ring.score = score
            val lc = ev.maxByOrNull { it.peak }!!
            loudClip = lc; loudBtn.visibility = View.VISIBLE
            loudBtn.text = "Play loudest clip - ${hmFmt.format(Date(lc.ts))}, ${"%.0f".format(lc.peak)} dBFS"
            statsTv.text = "${"%.1f".format(m)} min of snoring\n" +
                "${ev.size} events, longest ${"%.0f".format(ev.maxOf { it.durMs } / 1000.0)} s\n" +
                "Loudest ${"%.0f".format(peak)} dBFS" +
                (if (others.isNotEmpty() && others.average() > 0) "\n${"%+.1f".format(Locale.US, m - others.average())} min vs 7-night avg" else "")
        }
        ring.invalidate()

        val hrs = sess.filter { Night.key(it.first) == selected }.sumOf { it.second - it.first } / 3600_000.0
        val np = pauseAll.filter { Night.key(it.first) == selected }.sortedBy { it.first }
        tl.pauses = if (st.apnea) np else emptyList(); tl.invalidate()
        if (st.apnea && (np.isNotEmpty() || hrs > 0)) {
            val idx = if (hrs > 0.1) "%.1f".format(np.size / hrs) else "-"
            pauseStats.removeAllViews(); pauseChips.removeAllViews()
            fun stat(v: String, l: String) = pauseStats.addView(col(
                tv(v, M.style.TextAppearance_Material3_HeadlineSmall, pauseClr),
                tv(l, LABEL, clr(M.attr.colorOnSurfaceVariant))), LinearLayout.LayoutParams(0, WRAP, 1f))
            stat("${np.size}", "Pauses")
            if (np.isNotEmpty()) { stat(idx, "Per hour"); stat("${"%.0f".format(np.maxOf { it.second })} s", "Longest") }
            np.forEach { (ts, d) ->
                pauseChips.addView(Chip(this).apply {
                    text = "${hmFmt.format(Date(ts))} \u00b7 ${"%.0f".format(d)} s"
                    chipStrokeColor = ColorStateList.valueOf(pauseClr); chipStrokeWidth = dp(1).toFloat()
                    setOnClickListener { tl.focus(ts, (d * 1000).toLong()) }
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
            }
            pauseBox.visibility = View.VISIBLE
        } else pauseBox.visibility = View.GONE

        val curTags = tagsMap[selected] ?: emptySet()
        chips.forEach { (t, c) -> c.isChecked = t in curTags }
        val known = byNight.keys + tagsMap.keys
        insights.text = TAGS.joinToString("\n") { t ->
            val w = known.filter { t in (tagsMap[it] ?: emptySet()) }.map { mins[it] ?: 0.0 }
            val wo = known.filter { t !in (tagsMap[it] ?: emptySet()) }.map { mins[it] ?: 0.0 }
            if (w.size < 2 || wo.size < 2) "${L.t(t)}: need more nights (${w.size} with / ${wo.size} without)"
            else "${L.t(t)}: ${"%.1f".format(w.average())} min with vs ${"%.1f".format(wo.average())} min without"
        }

        list.removeAllViews()
        if (ev.isEmpty()) list.addView(tv("-", BODY))
        val ripple = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        val sub = clr(M.attr.colorOnSurfaceVariant)
        val groups = ArrayList<ArrayList<Ev>>()
        for (e in ev.sortedBy { it.ts }) {
            val g = groups.lastOrNull()
            if (g != null && e.ts - (g.last().ts + g.last().durMs) <= 60_000) g.add(e) else groups.add(arrayListOf(e))
        }
        fun clipRow(e: Ev, indent: Int) = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(indent), dp(12), 0, dp(12))
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { load(e) }
            addView(col(tv(timeFmt.format(Date(e.ts)), TITLE), tv(scoreText(e), LABEL, sub)), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(tv("${"%.1f".format(e.durMs / 1000.0)} s   ${"%.0f".format(e.peak)} dBFS", BODY, sub))
            addView(tv("   >", TITLE, clr(M.attr.colorPrimary)))
        }
        groups.take(200).forEach { g ->
            if (g.size == 1) { list.addView(clipRow(g[0], 0)); return@forEach }
            val key = g[0].id
            val open = key in openGroups
            val span = (g.last().ts + g.last().durMs - g[0].ts) / 1000
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12))
                setBackgroundResource(ripple.resourceId)
                setOnClickListener { load(g[0], g) }
                setOnLongClickListener { confirmDelete(g); true }
                addView(col(tv(timeFmt.format(Date(g[0].ts)), TITLE), tv("${g.size} clips merged, tap to play all", LABEL, sub)), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(tv("%d:%02d   %.0f dBFS".format(span / 60, span % 60, g.maxOf { it.peak }), BODY, sub))
                addView(tv(if (open) "   v" else "   >", TITLE, clr(M.attr.colorPrimary)).apply {
                    setPadding(dp(8), dp(8), dp(4), dp(8))
                    setOnClickListener { if (open) openGroups.remove(key) else openGroups.add(key); refresh() }
                })
            }
            list.addView(row)
            if (open) g.forEach { list.addView(clipRow(it, 16)) }
        }

        evCount = ev.size
        rejList.removeAllViews()
        val rj = rejAll.filter { Night.key(it.ts) == selected }.sortedBy { it.ts }
        rejCount = rj.size
        rejCard.visibility = if (rj.isEmpty()) View.GONE else View.VISIBLE
        rj.take(100).forEach { e ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10))
                setBackgroundResource(ripple.resourceId)
                setOnClickListener { load(e) }
                addView(col(tv(timeFmt.format(Date(e.ts)), TITLE), tv(L.t(e.reason) + scoreText(e).let { if (it.isEmpty()) "" else "  |  $it" }, LABEL, sub)), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(tv("${"%.1f".format(e.durMs / 1000.0)} s   ${"%.0f".format(e.peak)} dBFS", BODY, sub))
                addView(tv("   >", TITLE, clr(M.attr.colorPrimary)))
            }
            rejList.addView(row)
        }
        val fn = rejAll.count { Night.key(it.ts) == selected && it.reason.startsWith("fart") }
        if (fn > 0 || Settings(this).fart) statsTv.text = statsTv.text.toString() + "\nFarts: $fn"
        heads()
    }

    private fun exitInfo(): String {
        return try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val l = am.getHistoricalProcessExitReasons(packageName, 0, 1)
            if (l.isEmpty()) "" else {
                val e = l[0]
                val r = when (e.reason) {
                    1 -> "EXIT_SELF"; 2 -> "SIGNALED"; 3 -> "LOW_MEMORY"; 4 -> "CRASH"; 5 -> "CRASH_NATIVE"
                    6 -> "ANR"; 7 -> "INITIALIZATION_FAILURE"; 8 -> "PERMISSION_CHANGE"; 9 -> "EXCESSIVE_RESOURCE_USAGE"
                    10 -> "USER_REQUESTED"; 11 -> "USER_STOPPED"; 12 -> "DEPENDENCY_DIED"; 13 -> "OTHER"
                    14 -> "FREEZER"; 15 -> "PACKAGE_STATE_CHANGE"; 16 -> "PACKAGE_UPDATED"; else -> "UNKNOWN"
                }
                "\nAndroid says: $r at ${hmFmt.format(Date(e.timestamp))}" + (e.description?.let { " ($it)" } ?: "")
            }
        } catch (t: Throwable) { "" }
    }

    private fun showLog() {
        val t = tv(Diag.read(this, 120), LABEL).apply { setPadding(dp(20), dp(8), dp(20), dp(8)); setTextIsSelectable(true) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(L.t("Diagnostics")).setView(ScrollView(this).apply { addView(t) })
            .setPositiveButton(L.t("Close"), null).show()
    }

    private fun shareLog() {
        val f = Diag.file(this)
        if (!f.exists()) { toast("Log is empty"); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), L.t("Share log")))
    }

    private fun doBackup(uri: Uri) {
        toast("Backing up...")
        Thread {
            val res = try {
                val n = contentResolver.openOutputStream(uri)!!.use { Backup.write(this, it) }
                L.f("Backup saved (%d clips)", n)
            } catch (t: Throwable) { Diag.log(this, "backup failed: " + t.javaClass.simpleName + " " + t.message); L.t("Backup failed") }
            runOnUiThread { Toast.makeText(this, res, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun confirmRestore() {
        if (RecorderService.running) { toast("Disarm first"); return }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(L.t("Restore from backup?"))
            .setMessage(L.t("All current events, clips and settings on this phone are replaced by the backup. This cannot be undone."))
            .setNegativeButton(L.t("Cancel"), null)
            .setPositiveButton(L.t("Choose file")) { _, _ -> backupIn.launch(arrayOf("application/zip", "application/octet-stream")) }
            .show()
    }

    private fun doRestore(uri: Uri) {
        if (RecorderService.running) { toast("Disarm first"); return }
        toast("Restoring...")
        Thread {
            val res = try {
                val n = contentResolver.openInputStream(uri)!!.use { Backup.restore(this, it) }
                L.f("Restored from backup (%d clips)", n)
            } catch (t: Throwable) { Diag.log(this, "restore failed: " + t.javaClass.simpleName + " " + t.message); L.t("Restore failed: not a SnoreLog backup") }
            runOnUiThread { Toast.makeText(this, res, Toast.LENGTH_LONG).show(); recreate() }
        }.start()
    }

    private fun export() {
        val db = Db(this)
        val all = db.all(); val pauses = db.pauses(); val sess = db.sessions(); val tags = db.allTags()
        db.close()
        val dir = File(getExternalFilesDir(null), "exports").also { it.mkdirs() }
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fun n1(v: Double) = "%.1f".format(Locale.US, v)
        fun nightDate(k: Int) = dayFmt.format(Night.cal(k).time)

        val fe = File(dir, "snore_events.csv")
        fe.writeText("timestamp_iso,timestamp_ms,duration_s,peak_dbfs,mean_dbfs,ai_score\n" + all.joinToString("\n") {
            "${iso.format(Date(it.ts))},${it.ts},${n1(it.durMs / 1000.0)},${n1(it.peak)},${n1(it.mean)},${"%.2f".format(Locale.US, it.cls)}"
        })

        val fp = File(dir, "snore_pauses.csv")
        fp.writeText("timestamp_iso,timestamp_ms,night,duration_s\n" + pauses.sortedBy { it.first }.joinToString("\n") {
            "${iso.format(Date(it.first))},${it.first},${nightDate(Night.key(it.first))},${n1(it.second)}"
        })

        val byNight = all.groupBy { Night.key(it.ts) }
        val pauseBy = pauses.groupBy { Night.key(it.first) }
        val recBy = sess.groupBy { Night.key(it.first) }.mapValues { en -> en.value.sumOf { it.second - it.first } / 60000.0 }
        val fn = File(dir, "snore_nights.csv")
        fn.writeText("night,recorded_min,snoring_min,snoring_pct,events,longest_event_s,loudest_dbfs,p95_peak_dbfs,score,suspected_pauses,pauses_per_h,longest_pause_s,tags\n" +
            (byNight.keys + pauseBy.keys).toSortedSet().joinToString("\n") { k ->
                val ev = byNight[k] ?: emptyList()
                val pz = pauseBy[k] ?: emptyList()
                val rec = recBy[k] ?: 0.0
                val sc = Score.calc(ev, rec)
                val recOk = rec >= 30.0
                listOf(
                    nightDate(k),
                    if (recOk) n1(rec) else "",
                    n1(sc.snoreMin),
                    if (recOk) n1(sc.pct) else "",
                    ev.size.toString(),
                    if (ev.isEmpty()) "" else n1(ev.maxOf { it.durMs } / 1000.0),
                    if (ev.isEmpty()) "" else n1(ev.maxOf { it.peak }),
                    if (ev.isEmpty()) "" else n1(sc.p95),
                    if (ev.isEmpty()) "" else sc.score.toString(),
                    pz.size.toString(),
                    if (recOk) n1(pz.size / (rec / 60.0)) else "",
                    if (pz.isEmpty()) "" else n1(pz.maxOf { it.second }),
                    (tags[k] ?: emptySet()).sorted().joinToString(";")
                ).joinToString(",")
            })

        val uris = ArrayList(listOf(fn, fe, fp).map { FileProvider.getUriForFile(this, "$packageName.files", it) })
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/csv")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), L.t("Export")))
    }
}
