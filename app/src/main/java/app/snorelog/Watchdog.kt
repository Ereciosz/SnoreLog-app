package app.snorelog

import android.app.*
import android.content.*
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Notification channels. All start silent; the user can change each one in the system notification settings. */
object Chan {
    const val STOPPED = "stopped"   // monitoring was stopped by the system
    const val HEAT = "heat"         // paused because the phone is hot
    const val AUTO = "auto"         // auto-disarm, 12 h limit

    fun create(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(STOPPED, L.t("Stopped by the system"), NotificationManager.IMPORTANCE_LOW).apply { description = L.t("Monitoring was ended by Android or the watchdog found it not running") })
        nm.createNotificationChannel(NotificationChannel(HEAT, L.t("Phone too hot"), NotificationManager.IMPORTANCE_LOW).apply { description = L.t("Recording paused because the phone got hot") })
        nm.createNotificationChannel(NotificationChannel(AUTO, L.t("Auto-disarm and limits"), NotificationManager.IMPORTANCE_LOW).apply { description = L.t("Disarmed because you were awake, or the 12 h limit was reached") })
        nm.deleteNotificationChannel("alert")
        nm.deleteNotificationChannel("status")
    }
}

/** Inexact repeating alarm; alarms survive a process kill, so this revives the app's code. */
object Watchdog {
    private const val EVERY_MS = 15 * 60_000L

    private fun pi(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, WatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(ctx: Context) {
        try {
            ctx.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + EVERY_MS, pi(ctx))
        } catch (t: Throwable) { Diag.log(ctx, "watchdog schedule failed: " + t.javaClass.simpleName) }
    }

    fun cancel(ctx: Context) {
        try { ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx)) } catch (t: Throwable) { }
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val st = Settings(ctx)
        if (!st.armed || !st.watchdog) return
        if (!RecorderService.running) {
            Diag.log(ctx, "watchdog: service is not running although armed")
            val nm = ctx.getSystemService(NotificationManager::class.java)
            Chan.create(ctx)
            val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(2, NotificationCompat.Builder(ctx, Chan.STOPPED)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(L.t("SnoreLog was stopped by the system"))
                .setContentText(L.t("Tap to open and arm again"))
                .setContentIntent(open).setAutoCancel(true).build())
        }
        Watchdog.schedule(ctx)
    }
}

/** Optional: keeps the app visible (foreground) with a black, dimmed screen. */
class BedsideActivity : AppCompatActivity() {
    companion object { @Volatile var active = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.also { it.screenBrightness = 0.01f }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(TextView(context).apply {
                text = L.t("Bedside mode - long-press to exit")
                setTextColor(0xFF2A2A2A.toInt()); textSize = 12f
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            setOnLongClickListener { finish(); true }
        }
        setContentView(root)
        if (!RecorderService.running) {
            try { ContextCompat.startForegroundService(this, Intent(this, RecorderService::class.java)) } catch (t: Throwable) { }
        }
    }

    override fun onStart() { super.onStart(); active = true }
    override fun onStop() { super.onStop(); active = false }
}
