package app.snorelog

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Foreground microphone service with two states:
 *  - armed: waiting for the charger (no mic use, no wake lock)
 *  - recording: listening while charging
 */
class RecorderService : Service() {
    private class Pend(val ts: Long, val dur: Long, val peak: Double, val mean: Double, val file: File,
                       val cls: Double, val band: Double, val lv: String, val thr: Double)

    companion object {
        const val ACTION_STOP = "stop"
        @Volatile var running = false    // armed or recording
        @Volatile var recording = false
        @Volatile var detail = ""        // why we are armed but not recording
    }

    @Volatile private var recStop = true
    @Volatile private var want = false
    private var thread: Thread? = null
    private var wl: PowerManager.WakeLock? = null
    private var rxRegistered = false

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = Settings(this@RecorderService)
            Diag.log(this@RecorderService, "broadcast " + i.action)
            if (i.action == Intent.ACTION_SCREEN_ON) { screenForLimit(true); alarmScreen(true) }
            else if (i.action == Intent.ACTION_SCREEN_OFF) { screenForLimit(false); alarmScreen(false) }
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> maybeRecord()
                Intent.ACTION_POWER_DISCONNECTED -> {
                    if (alarmAt > 0 && st.unplugMode == 1 && System.currentTimeMillis() >= alarmAt) { alarmStop.run(); return }
                    handler.removeCallbacks(delayedStart)
                    if (!powerOk()) {
                        stopRecording()
                        if (st.unplugMode == 2) stopSelf()
                    }
                }
                Intent.ACTION_SCREEN_OFF -> if (st.screenOffOnly && powerOk()) {
                    handler.removeCallbacks(delayedStart)
                    handler.postDelayed(delayedStart, st.screenDelay * 60_000L)
                }
                Intent.ACTION_SCREEN_ON -> if (st.screenOffOnly && !BedsideActivity.active) {
                    handler.removeCallbacks(delayedStart)
                    stopRecording()
                    setNotif("SnoreLog armed", "Paused while the screen is on (screen-off-only is enabled)")
                }
            }
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    private val handler = Handler(Looper.getMainLooper())
    private val delayedStart = Runnable { maybeRecord() }

    private var armedForSleep = false
    private val sleepMark = Runnable { armedForSleep = true; Diag.log(this, "screen off 10 min: sleep assumed") }
    private val awakeStop = object : Runnable {
        override fun run() {
            if (!running) return
            if (BedsideActivity.active) { handler.postDelayed(this, 60_000L); return }
            val min = Settings(this@RecorderService).alarmLimit
            Diag.log(this@RecorderService, "auto-disarm: awake for $min min")
            val open = PendingIntent.getActivity(this@RecorderService, 3, Intent(this@RecorderService, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            getSystemService(NotificationManager::class.java).notify(3, Notification.Builder(this@RecorderService, Chan.AUTO)
                .setSmallIcon(R.drawable.ic_night).setContentTitle(L.t("SnoreLog disarmed"))
                .setContentText(L.t("You have been awake for $min min, so monitoring stopped."))
                .setContentIntent(open).setAutoCancel(true).build())
            stopSelf()
        }
    }

    /** No-alarm fallback of "disarm when I get up": phone in use for too long, but only after it has been asleep (screen off 10+ min) once. */
    private fun screenForLimit(on: Boolean) {
        handler.removeCallbacks(sleepMark); handler.removeCallbacks(awakeStop)
        val st = Settings(this)
        // fallback only: with a Clock alarm the alarm rules decide, without one we count once the phone has been asleep
        if (!st.alarmOn || alarmAt != 0L) return
        val lim = st.alarmLimit
        if (on) { if (armedForSleep) handler.postDelayed(awakeStop, lim * 60_000L) }
        else handler.postDelayed(sleepMark, 10 * 60_000L)
    }

    /** Next Clock-app alarm seen at arm time (within 14 h), or 0. After it, continuous screen-on for the limit disarms. */
    private var alarmAt = 0L
    private val alarmTick = Runnable { alarmScreen(interactive()) }
    private val alarmStop = Runnable {
        if (!running) return@Runnable
        Diag.log(this, "auto-disarm after alarm")
        val open = PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(3, Notification.Builder(this, Chan.AUTO)
            .setSmallIcon(R.drawable.ic_night).setContentTitle(L.t("SnoreLog disarmed"))
            .setContentText(L.t("You are up after your alarm, so monitoring stopped.")).setContentIntent(open).setAutoCancel(true).build())
        stopSelf()
    }

    private fun alarmScreen(on: Boolean) {
        handler.removeCallbacks(alarmStop)
        val lim = Settings(this).alarmLimit
        if (lim <= 0 || alarmAt == 0L || System.currentTimeMillis() < alarmAt) return
        if (on && !BedsideActivity.active) handler.postDelayed(alarmStop, lim * 60_000L)
    }

    private fun setupAlarm() {
        handler.removeCallbacks(alarmTick); handler.removeCallbacks(alarmStop)
        alarmAt = 0L
        if (!Settings(this).alarmOn) return
        val t = try { getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime ?: 0L } catch (_: Throwable) { 0L }
        val now = System.currentTimeMillis()
        if (t > now && t - now <= 14 * 3600_000L) {
            alarmAt = t
            handler.postDelayed(alarmTick, t - now + 1000L)
            Diag.log(this, "alarm at ${java.util.Date(t)}; screen-on limit ${Settings(this).alarmLimit} min, unplug mode ${Settings(this).unplugMode}")
        } else Diag.log(this, "no Clock alarm within 14 h")
    }

    private fun interactive() = getSystemService(PowerManager::class.java).isInteractive

    @Volatile private var heatPaused = false
    private var lastTemp = 0.0

    private fun tempC(): Double =
        (registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

    /** True while recording must stay paused because the phone is hot. Pauses at the limit, resumes 4 deg C below it. */
    private fun heatBlock(): Boolean {
        val st = Settings(this)
        if (!st.heatGuard) { heatPaused = false; return false }
        val t = tempC(); lastTemp = t
        val th = getSystemService(PowerManager::class.java).currentThermalStatus
        val was = heatPaused
        heatPaused = if (was) !(t <= st.heatLimit - 4 && th < PowerManager.THERMAL_STATUS_MODERATE)
                     else (t >= st.heatLimit || th >= PowerManager.THERMAL_STATUS_SEVERE)
        if (heatPaused && !was) {
            Diag.log(this, "heat pause: %.1f C, thermal status %d".format(java.util.Locale.US, t, th))
            val open = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            getSystemService(NotificationManager::class.java).notify(5, Notification.Builder(this, Chan.HEAT)
                .setSmallIcon(R.drawable.ic_night).setContentTitle(L.t("Phone is hot - recording paused"))
                .setContentText(L.t("%.1f C. Recording resumes when it cools down.".format(java.util.Locale.US, t)))
                .setContentIntent(open).setAutoCancel(true).build())
            handler.postDelayed(heatCheck, 60_000L)
        }
        if (!heatPaused && was) { Diag.log(this, "heat pause over: %.1f C".format(java.util.Locale.US, t)); getSystemService(NotificationManager::class.java).cancel(5) }
        return heatPaused
    }

    private val heatCheck = object : Runnable {
        override fun run() {
            if (!running || !heatPaused) return
            if (heatBlock()) { setNotif("SnoreLog armed", armedText()); handler.postDelayed(this, 60_000L) }
            else maybeRecord()
        }
    }

    private fun armedText(): String =
        if (heatPaused) "Paused: phone is hot (%.0f C)".format(java.util.Locale.US, lastTemp)
        else if (!powerOk()) (if (Settings(this).batteryMode && !charging()) "Waiting: battery below ${Settings(this).batteryFloor}%" else "Waiting for charger")
        else if (Settings(this).screenOffOnly && interactive()) "Paused while the screen is on (screen-off-only is enabled)"
        else "Waiting"

    /** Start recording if charging and (when "screen off only" is on) the screen is off. */
    private fun maybeRecord() {
        if (heatBlock()) setNotif("SnoreLog armed", armedText())
        else if (powerOk() && (!Settings(this).screenOffOnly || !interactive())) startRecording()
        else setNotif("SnoreLog armed", armedText())
    }

    @Volatile private var lowBatPaused = false

    private fun batteryPct(): Int {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val l = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val s = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (l < 0 || s <= 0) 100 else l * 100 / s
    }

    /** Charging, or (optional battery mode) battery at or above the floor. Resumes 5 points above the floor. */
    private fun powerOk(): Boolean {
        if (charging()) { lowBatPaused = false; return true }
        val st = Settings(this)
        if (!st.batteryMode) return false
        val p = batteryPct()
        lowBatPaused = if (lowBatPaused) p < st.batteryFloor + 5 else p < st.batteryFloor
        return !lowBatPaused
    }

    private fun charging(): Boolean =
        (registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    private fun notif(title: String, text: String): Notification {
        val stopPi = PendingIntent.getService(this, 0, Intent(this, RecorderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        return Notification.Builder(this, "rec")
            .setSmallIcon(R.drawable.ic_night)
            .setContentTitle(L.t(title))
            .setContentText(L.t(text))
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, L.t("Disarm"), stopPi)
            .setOngoing(true)
            .build()
    }

    private fun setNotif(title: String, text: String) {
        detail = text
        getSystemService(NotificationManager::class.java).notify(1, notif(title, text))
        Widget.update(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rec", L.t("Snore monitoring"), NotificationManager.IMPORTANCE_LOW))
        Chan.create(this)
        nm.cancel(2)
        if (intent == null) Diag.log(this, "restarted by system (sticky)")
        try {
            ServiceCompat.startForeground(this, 1, notif("SnoreLog armed", "Waiting for charger"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (t: Throwable) {
            Diag.log(this, "cannot start foreground after restart: " + t.javaClass.simpleName)
            val pi = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(2, androidx.core.app.NotificationCompat.Builder(this, Chan.STOPPED)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(L.t("SnoreLog was stopped by the system"))
                .setContentText(L.t("Tap to open and arm again")).setContentIntent(pi).setAutoCancel(true).build())
            stopSelf(); return START_NOT_STICKY
        }
        running = true
        Settings(this).armed = true
        Diag.log(this, "armed; " + status())
        Widget.update(this)

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, rx, f, ContextCompat.RECEIVER_EXPORTED)
        rxRegistered = true
        if (Settings(this).watchdog) Watchdog.schedule(this)
        armedForSleep = false
        setupAlarm()
        screenForLimit(interactive())
        maybeRecord()
        return START_STICKY
    }

    override fun onDestroy() {
        Diag.log(this, "service onDestroy (clean stop)")
        Watchdog.cancel(this)
        running = false
        want = false
        recStop = true
        handler.removeCallbacksAndMessages(null)
        thread?.join(3000)
        try { if (rxRegistered) unregisterReceiver(rx) } catch (t: Throwable) { }
        wl?.takeIf { it.isHeld }?.release()
        recording = false
        Settings(this).armed = false
        Widget.update(this)
        super.onDestroy()
    }

    @Volatile private var failed = false

    private fun status(): String {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val plug = b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val pm = getSystemService(PowerManager::class.java)
        return "batt=$lvl% temp=${temp}C plugged=$plug screenOn=${pm.isInteractive} thermal=${pm.currentThermalStatus}"
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Diag.log(this, "task removed (app swiped from recents)")
        super.onTaskRemoved(rootIntent)
    }

    @Synchronized private fun startRecording() {
        want = true
        if (thread?.isAlive == true) return
        recStop = false
        recording = true
        wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "snorelog:rec").also { it.acquire(13 * 3600_000L) }
        setNotif("SnoreLog recording", "Listening while charging")
        failed = false
        Diag.log(this, "recording start; " + status())
        thread = Thread {
            try { loop() }
            catch (t: Throwable) { failed = true; Diag.log(this, "recorder crashed: " + t.stackTraceToString().take(1500)) }
            finally { onRecEnd() }
        }.also { it.start() }
    }

    private fun stopRecording() {
        want = false
        recStop = true
    }

    private fun onRecEnd() {
        recording = false
        wl?.takeIf { it.isHeld }?.release()
        Diag.log(this, "recording ended (want=$want failed=$failed)")
        if (running && want) {
            // restart from the main thread: this thread is still alive while we are here
            handler.postDelayed({ if (running && want) startRecording() }, if (failed) 10_000L else 500L)
        } else if (running) setNotif("SnoreLog armed", armedText())
    }

    private fun loop() {
        val sr = Cfg.SAMPLE_RATE
        val frame = sr / 10
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, frame * 8))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            want = false
            setNotif("SnoreLog armed", "Microphone unavailable - open the app and arm again")
            return
        }
        val db = Db(this)
        db.purge(Settings(this).retention, 3)
        val dir = File(getExternalFilesDir(null), "clips").also { it.mkdirs() }

        val st = Settings(this)
        val margin = st.margin.toDouble()
        val nudge = st.nudge
        val apnea = st.apnea
        val keepRej = st.keepRej
        val yam = Yamnet(this).takeIf { it.ok }
        val startedAt = System.currentTimeMillis()
        val sid = db.startSession(startedAt)
        Diag.log(this, "session $sid: margin=$margin classifier=${yam != null} apnea=$apnea screenOffOnly=${st.screenOffOnly}")

        val buf = ShortArray(frame)
        val ringN = sr * 5
        val ring = ShortArray(ringN)
        var rp = 0; var filled = 0
        val clip = ShortArray(sr * 12)  // first ~12 s of an event, for the classifier
        var clipLen = 0
        fun clipAdd(a: ShortArray, off: Int, len: Int) {
            val k = min(len, clip.size - clipLen)
            if (k > 0) { System.arraycopy(a, off, clip, clipLen, k); clipLen += k }
        }

        var floor = Double.NaN
        var hot = 0; var quiet = 0; var n = 0
        var active = false
        var wav: AacWriter? = null
        var file: File? = null
        var evStart = 0L; var peak = -120.0; var sum = 0.0
        var loudFrames = 0
        var lastNudge = 0L; var frames = 0
        var lastLoud = 0; var loudRecent = 0; var gapBefore = 0; var priorLoud = 0; var gapMarkFrame = 0
        var zeroFrames = 0; var silentWarn = false
        var errs = 0
        val lvRing = FloatArray(50); var lvRp = 0; var lvFill = 0
        val lvAct = ArrayList<Float>(700)
        var evThr = 0.0

        fun save(p: Pend) { db.insert(p.ts, p.dur, p.peak, p.mean, p.file.absolutePath, p.cls, p.band, false, "", p.lv, p.thr); Widget.update(this) }
        fun reject(p: Pend, why: String) {
            if (why == "short" || (!keepRej && !why.startsWith("fart"))) p.file.delete()
            else db.insert(p.ts, p.dur, p.peak, p.mean, p.file.absolutePath, p.cls, p.band, true, why, p.lv, p.thr)
        }

        fun finish() {
            wav?.close()
            val f = file!!
            val dur = System.currentTimeMillis() - evStart
            val bandV = -1.0
            val p = if (dur >= 800 && yam != null) yam.snoreProb(clip, clipLen).toDouble() else -1.0
            val aiMin = Settings(this).aiPct / 100f
            val fartOn = Settings(this).fart && yam != null && p >= 0 && yam.lastFart >= 0.35f && yam.lastFart > p
            val why = when {
                dur < 800 -> "short"
                fartOn -> "fart %.2f".format(Locale.US, yam!!.lastFart)
                yam != null && p < aiMin -> "AI %.2f".format(Locale.US, p)
                else -> ""
            }
            val sb = StringBuilder()
            for (v in lvAct) { if (sb.isNotEmpty()) sb.append(','); sb.append((v * 10).toInt()) }
            val cur = Pend(evStart, dur, peak, sum / max(n, 1), f, p, bandV, sb.toString(), evThr)
            if (why.isNotEmpty()) reject(cur, why)
            else save(cur)
            wav = null; active = false; hot = 0; filled = 0
            quiet = 0; n = 0; clipLen = 0; loudFrames = 0
            lvAct.clear(); lvFill = 0
        }

        rec.startRecording()
        while (!recStop) {
            val r = rec.read(buf, 0, frame)
            if (r < 0) break
            if (r == 0) continue
            try {
            frames++
            if (frames % 1200 == 0) Diag.log(this, "heartbeat frames=$frames active=$active; " + status())
            if (frames % 600 == 0) db.endSession(sid, System.currentTimeMillis())
            if (frames % 100 == 0 && heatBlock()) { want = false; recStop = true }
            if (frames % 100 == 0 && !powerOk()) {
                Diag.log(this, "power lost or battery below floor (${batteryPct()}%), stopping recording")
                if (Settings(this).batteryMode) {
                    val open = PendingIntent.getActivity(this, 6, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                    getSystemService(NotificationManager::class.java).notify(6, Notification.Builder(this, Chan.AUTO)
                        .setSmallIcon(R.drawable.ic_night).setContentTitle(L.t("Battery low - recording stopped"))
                        .setContentText(L.t("Below ${Settings(this).batteryFloor}%. Connect the charger to resume."))
                        .setContentIntent(open).setAutoCancel(true).build())
                }
                want = false; recStop = true
            }
            if (frames % 100 == 0 && System.currentTimeMillis() - startedAt > 12 * 3600_000L) {
                Diag.log(this, "12 h limit reached, stopping")
                val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                getSystemService(NotificationManager::class.java).notify(3, Notification.Builder(this, Chan.AUTO)
                    .setSmallIcon(R.drawable.ic_night).setContentTitle(L.t("SnoreLog stopped"))
                    .setContentText(L.t("Reached the 12 h recording limit.")).setContentIntent(open).setAutoCancel(true).build())
                want = false; stopSelf(); break
            }

            var s = 0.0; var zc = 0
            for (i in 0 until r) {
                val v = buf[i].toDouble(); s += v * v
                if (i > 0 && (buf[i] >= 0) != (buf[i - 1] >= 0)) zc++
            }
            // the system silences the mic of apps without access: all samples exactly 0
            if (s == 0.0) zeroFrames++ else zeroFrames = 0
            if (zeroFrames >= 100 && !silentWarn) {
                silentWarn = true
                setNotif("SnoreLog recording", "Microphone looks silenced by the system - open the app and arm again while charging")
            } else if (zeroFrames == 0 && silentWarn) {
                silentWarn = false
                setNotif("SnoreLog recording", "Listening while charging")
            }
            val d = 20 * log10(sqrt(s / r) / 32768.0 + 1e-9)
            val zcr = zc.toDouble() / r
            if (floor.isNaN()) floor = d
            val thr = floor + margin
            val loud = d > thr && zcr < Cfg.MAX_ZCR

            if (loud) {
                val g = frames - lastLoud
                if (g >= 100) { gapBefore = g; priorLoud = loudRecent; loudRecent = 0; gapMarkFrame = frames }
                lastLoud = frames; loudRecent++
            }

            if (!active) {
                if (!loud) floor += (d - floor) * 0.02
                for (i in 0 until r) { ring[rp] = buf[i]; rp = (rp + 1) % ringN }
                lvRing[lvRp] = d.toFloat(); lvRp = (lvRp + 1) % 50; lvFill = min(50, lvFill + 1)
                filled = min(ringN, filled + r)
                if (loud) hot++ else hot = 0
                if (hot >= 3) {
                    // suspected pause: >=10 s (<=90 s) silence after >=3 s of loud breathing, then resumption
                    if (apnea && gapBefore in 100..900 && priorLoud >= 30 && frames - gapMarkFrame < 50) {
                        db.insertPause(System.currentTimeMillis() - (frames - gapMarkFrame + gapBefore) * 100L, gapBefore / 10.0)
                    }
                    gapBefore = 0
                    active = true
                    evStart = System.currentTimeMillis() - (filled * 1000L / sr)
                    file = File(dir, "snore_$evStart.m4a")
                    wav = AacWriter(file!!, sr).also { w ->
                        if (filled < ringN) { w.write(ring, 0, filled); clipAdd(ring, 0, filled) }
                        else {
                            w.write(ring, rp, ringN - rp); w.write(ring, 0, rp)
                            clipAdd(ring, rp, ringN - rp); clipAdd(ring, 0, rp)
                        }
                    }
                    loudFrames = hot
                    lvAct.clear()
                    for (k in 0 until lvFill) lvAct.add(lvRing[(lvRp - lvFill + k + 100) % 50])
                    evThr = thr
                    peak = d; sum = d; n = 1
                }
            } else {
                wav!!.write(buf, 0, r)
                lvAct.add(d.toFloat())
                clipAdd(buf, 0, r)
                if (loud) loudFrames++
                peak = max(peak, d); sum += d; n++
                if (nudge && n == 150 && System.currentTimeMillis() - lastNudge > 300_000L) {
                    lastNudge = System.currentTimeMillis()
                    getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
                }
                quiet = if (d > thr - 3) 0 else quiet + 1
                if (quiet >= Cfg.END_QUIET_FRAMES || n >= Cfg.MAX_EVENT_FRAMES) {
                    if (n >= Cfg.MAX_EVENT_FRAMES) floor = d
                    finish()
                }
            }
            } catch (t: Throwable) {
                errs++
                Diag.log(this, "frame error #$errs: " + t.stackTraceToString().take(1200))
                if (errs > 20) throw t
                try { wav?.close() } catch (x: Throwable) { }
                file?.delete()
                wav = null; active = false; hot = 0; filled = 0
                quiet = 0; n = 0; clipLen = 0; loudFrames = 0
                lvAct.clear(); lvFill = 0
            }
        }
        Diag.log(this, "loop exit (recStop=$recStop frames=$frames)")
        if (active) finish()
        db.endSession(sid, System.currentTimeMillis())
        rec.stop(); rec.release(); db.close(); yam?.close()
    }
}
