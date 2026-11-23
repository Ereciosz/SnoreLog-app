package app.snorelog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SnoreWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) = Widget.update(ctx)
    override fun onAppWidgetOptionsChanged(ctx: Context, m: AppWidgetManager, id: Int, o: Bundle) = Widget.update(ctx)
}

/** Home-screen widget: arm/disarm button plus last-night stats. Resizable; 2x2 is compact, wider shows more. */
object Widget {
    fun update(ctx: Context) {
        try {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SnoreWidget::class.java))
            if (ids.isEmpty()) return
            val data = load(ctx)
            for (id in ids) {
                val w = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
                mgr.updateAppWidget(id, build(ctx, data, w >= 230))
            }
        } catch (t: Throwable) { Diag.log(ctx, "widget update failed: " + t.javaClass.simpleName) }
    }

    private class Data(val status: String, val running: Boolean, val big: String, val detail: String, val stats: String)

    private fun load(ctx: Context): Data {
        val st = Settings(ctx)
        val run = RecorderService.running
        val status = when {
            RecorderService.recording -> "Recording"
            run -> "Armed - waiting"
            st.armed -> "Stopped by system"
            else -> "Not armed"
        }
        val db = Db(ctx)
        val (all, sessions) = try { db.all() to db.sessions() } finally { db.close() }
        val now = System.currentTimeMillis()
        val cur = Night.key(now)
        val prev = Night.keyOf(Night.cal(cur).also { it.add(java.util.Calendar.DAY_OF_MONTH, -1) })
        val byNight = all.groupBy { Night.key(it.ts) }
        val key = if (byNight[cur].isNullOrEmpty() && !byNight[prev].isNullOrEmpty()) prev else cur
        val ev = (byNight[key] ?: emptyList()).sortedBy { it.ts }
        val label = if (key == cur) "This night" else "Last night"
        if (ev.isEmpty()) return Data(status, run, "--", "No events yet", "No events")
        val m = ev.sumOf { it.durMs } / 60000.0
        val peak = ev.maxOf { it.peak }
        val score = Score.calc(ev, sessions.filter { Night.key(it.first) == key }.sumOf { it.second - it.first } / 60000.0).score
        val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
        return Data(status, run, "%.0f min".format(Locale.US, m), "$label - ${ev.size} events",
            "$label\nScore $score\n${ev.size} events\nLoudest %.0f dBFS\n${hm.format(Date(ev.first().ts))} - ${hm.format(Date(ev.last().ts))}".format(Locale.US, peak))
    }

    private fun build(ctx: Context, d: Data, wide: Boolean): RemoteViews {
        val v = RemoteViews(ctx.packageName, if (wide) R.layout.widget_wide else R.layout.widget_small)
        v.setTextViewText(R.id.w_status, "SnoreLog - " + L.t(d.status))
        v.setTextViewText(R.id.w_big, d.big)
        v.setTextViewText(R.id.w_detail, L.t(d.detail))
        if (wide) v.setTextViewText(R.id.w_stats, L.t(d.stats))
        v.setTextViewText(R.id.w_btn, L.t(if (d.running) "Disarm" else "Arm"))
        v.setInt(R.id.w_btn, "setBackgroundResource", if (d.running) R.drawable.widget_btn2 else R.drawable.widget_btn)
        v.setTextColor(R.id.w_btn, ctx.getColor(if (d.running) R.color.w_btn2_on else R.color.w_btn_on))

        val svc = Intent(ctx, RecorderService::class.java)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val action = if (d.running) PendingIntent.getService(ctx, 11, svc.setAction(RecorderService.ACTION_STOP), flags)
                     else PendingIntent.getForegroundService(ctx, 12, svc, flags)
        v.setOnClickPendingIntent(R.id.w_btn, action)
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 13, Intent(ctx, MainActivity::class.java), flags))
        return v
    }
}
