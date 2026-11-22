package app.snorelog

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

/** A "night" belongs to the date of the evening it started: events from 12:00 to 12:00 next day. */
object Night {
    fun keyOf(c: Calendar) = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    fun key(ts: Long) = keyOf(Calendar.getInstance().apply { timeInMillis = ts - 12 * 3600_000L })
    fun cal(key: Int): Calendar = Calendar.getInstance().apply { clear(); set(key / 10000, (key / 100) % 100 - 1, key % 100) }
    fun heat(t: Double, base: Int): Int = Color.argb((70 + 185 * t.coerceIn(0.0, 1.0)).toInt(), Color.red(base), Color.green(base), Color.blue(base))
}

private fun View.dp(v: Float) = v * resources.displayMetrics.density
private fun View.attr(a: Int) = MaterialColors.getColor(this, a, Color.GRAY)
private val View.on get() = attr(com.google.android.material.R.attr.colorOnSurface)
private val View.sub get() = attr(com.google.android.material.R.attr.colorOnSurfaceVariant)
private val View.pri get() = attr(com.google.android.material.R.attr.colorPrimary)
private val View.grid get() = (on and 0x00FFFFFF) or 0x33000000
private val View.axis get() = (on and 0x00FFFFFF) or 0x55000000

class CalendarView(c: Context) : View(c) {
    var month: Calendar = Calendar.getInstance()
    var data: Map<Int, Double> = emptyMap()
    var selected = 0
    var onPick: (Int) -> Unit = {}
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cell = 0f
    private val headH get() = cell * 0.5f

    override fun onMeasure(w: Int, h: Int) {
        val ww = MeasureSpec.getSize(w)
        setMeasuredDimension(ww, (ww / 7f * 6.5f).toInt())
    }

    private fun offset(): Int {
        val c = month.clone() as Calendar
        c.set(Calendar.DAY_OF_MONTH, 1)
        return (c.get(Calendar.DAY_OF_WEEK) - 2 + 7) % 7 // Monday first
    }

    private fun keyFor(d: Int) = month.get(Calendar.YEAR) * 10000 + (month.get(Calendar.MONTH) + 1) * 100 + d

    override fun onDraw(cv: Canvas) {
        cell = width / 7f
        val off = offset()
        val days = month.getActualMaximum(Calendar.DAY_OF_MONTH)
        val mx = max(data.values.maxOrNull() ?: 1.0, 1.0)
        p.textAlign = Paint.Align.CENTER; p.textSize = cell * 0.32f; p.style = Paint.Style.FILL
        p.color = sub
        L.t("MTWTFSS").forEachIndexed { i, ch -> cv.drawText(ch.toString(), cell * (i + .5f), headH * .7f, p) }
        for (d in 1..days) {
            val idx = off + d - 1
            val cx = cell * (idx % 7 + .5f)
            val cy = headH + cell * (idx / 7 + .5f)
            val key = keyFor(d)
            data[key]?.let { m ->
                p.style = Paint.Style.FILL; p.color = Night.heat(m / mx, pri)
                cv.drawCircle(cx, cy, cell * .4f, p)
            }
            if (key == selected) {
                p.style = Paint.Style.STROKE; p.strokeWidth = dp(2f); p.color = on
                cv.drawCircle(cx, cy, cell * .42f, p)
            }
            p.style = Paint.Style.FILL; p.color = on
            cv.drawText(d.toString(), cx, cy + p.textSize * .35f, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && e.y > headH && cell > 0) {
            val col = (e.x / cell).toInt().coerceIn(0, 6)
            val row = ((e.y - headH) / cell).toInt()
            val d = row * 7 + col - offset() + 1
            if (d in 1..month.getActualMaximum(Calendar.DAY_OF_MONTH)) {
                selected = keyFor(d); onPick(selected); invalidate()
            }
        }
        return true
    }
}

class TimelineView(c: Context) : View(c) {
    var events: List<Ev> = emptyList()
    var key = 0
    var pauses: List<Pair<Long, Double>> = emptyList()
    var onPick: (Ev) -> Unit = {}
    var selId = -1L
    private var vLo = 0L
    private var vHi = 0L
    private var lastKey = -1
    private var showFull = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fmtH = SimpleDateFormat("HH", Locale.getDefault())
    private val fmtHm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val PL get() = dp(6f)
    private val PR get() = width - dp(6f)

    private fun full(): Pair<Long, Long> {
        val hr = 3600_000L
        val base = Night.cal(key).timeInMillis
        var lo = base + 20 * hr
        var hi = base + 34 * hr
        if (events.isNotEmpty()) {
            lo = min(lo, events.minOf { it.ts } / hr * hr)
            hi = max(hi, (events.maxOf { it.ts + it.durMs } / hr + 1) * hr)
        }
        return lo to hi
    }

    private fun range(): Pair<Long, Long> {
        val f = full()
        if (key != lastKey) { lastKey = key; vLo = 0; vHi = 0; showFull = false }
        if (vHi > vLo) return vLo to vHi
        if (!showFull && events.isNotEmpty()) return fit(f)
        return f
    }

    /** Just the events with a little margin, so empty hours are not shown. */
    private fun fit(f: Pair<Long, Long>): Pair<Long, Long> {
        val a = events.minOf { it.ts }
        val b = events.maxOf { it.ts + it.durMs }
        val pad = max(10 * 60_000L, ((b - a) * 0.08).toLong())
        return max(f.first, a - pad) to min(f.second, b + pad)
    }

    private fun setView(lo0: Long, hi0: Long) {
        val (fl, fh) = full()
        val minSpan = 5 * 60_000L
        var span = (hi0 - lo0).coerceIn(minSpan, fh - fl)
        var lo = lo0
        if (lo < fl) lo = fl
        if (lo + span > fh) lo = fh - span
        vLo = lo; vHi = lo + span
        if (vHi - vLo >= fh - fl) { vLo = 0; vHi = 0; showFull = true }
        invalidate()
    }

    private val scaler = android.view.ScaleGestureDetector(c, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            val (lo, hi) = range()
            val span = (hi - lo).toDouble()
            val fx = ((d.focusX - PL) / (PR - PL)).coerceIn(0f, 1f)
            val t = lo + span * fx
            val ns = span / d.scaleFactor
            setView((t - ns * fx).toLong(), (t - ns * fx + ns).toLong())
            return true
        }
    })

    private val taps = android.view.GestureDetector(c, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(a: MotionEvent?, b: MotionEvent, dx: Float, dy: Float): Boolean {
            if (kotlin.math.abs(dx) > kotlin.math.abs(dy) || vHi > vLo) parent?.requestDisallowInterceptTouchEvent(true)
            else return false
            val (lo, hi) = range()
            val dt = (dx / (PR - PL) * (hi - lo)).toLong()
            setView(lo + dt, hi + dt)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (vHi > vLo) { vLo = 0; vHi = 0 } else showFull = !showFull
            invalidate(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val (lo, hi) = range()
            val span = (hi - lo).toFloat()
            val tt = lo + (e.x - PL) / (PR - PL) * span
            val tol = dp(14f) / (PR - PL) * span
            val best = events.filter { tt >= it.ts - tol && tt <= it.ts + it.durMs + tol }
                .minByOrNull { kotlin.math.abs((it.ts + it.durMs / 2) - tt) }
            if (best != null) { selId = best.id; invalidate(); onPick(best) }
            return true
        }
    })

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount > 1) parent?.requestDisallowInterceptTouchEvent(true)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) parent?.requestDisallowInterceptTouchEvent(false)
        scaler.onTouchEvent(ev)
        if (!scaler.isInProgress) taps.onTouchEvent(ev)
        return true
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(190f).toInt())

    override fun onDraw(cv: Canvas) {
        val hr = 3600_000L
        fmtH.timeZone = java.util.TimeZone.getDefault(); fmtHm.timeZone = java.util.TimeZone.getDefault()
        val (lo, hi) = range()
        val base = Night.cal(key).timeInMillis
        val l = PL; val r = PR; val top = dp(8f); val bot = height - dp(22f)
        val span = (hi - lo).toFloat()
        val steps = longArrayOf(5 * 60_000L, 10 * 60_000L, 30 * 60_000L, hr, 2 * hr, 3 * hr)
        val step = steps.firstOrNull { span / it <= 7 } ?: steps.last()
        val fmt = if (step < hr) fmtHm else fmtH
        p.textSize = dp(11f); p.textAlign = Paint.Align.CENTER; p.strokeWidth = 1f
        var t = base + ((lo - base + step - 1) / step) * step
        while (t <= hi) {
            val x = l + (t - lo) / span * (r - l)
            p.style = Paint.Style.STROKE; p.color = grid; cv.drawLine(x, top, x, bot, p)
            p.style = Paint.Style.FILL; p.color = sub; cv.drawText(fmt.format(Date(t)), x, height - dp(6f), p)
            t += step
        }
        p.style = Paint.Style.STROKE; p.color = axis; cv.drawLine(l, bot, r, bot, p)
        p.style = Paint.Style.FILL
        cv.save(); cv.clipRect(l, 0f, r, bot + 1f)
        p.color = 0x5500BCD4
        for ((pt, pd) in pauses) {
            val x = l + (pt - lo) / span * (r - l)
            cv.drawRect(x, top, x + max(dp(3f), (pd * 1000 / span).toFloat() * (r - l)), bot, p)
        }
        for (e in events) {
            if (e.ts + e.durMs < lo || e.ts > hi) continue
            val x = l + (e.ts - lo) / span * (r - l)
            val w = max(dp(2f), e.durMs / span * (r - l))
            val lvl = ((e.peak + 60) / 60).coerceIn(0.08, 1.0)
            p.color = Night.heat(lvl, pri)
            cv.drawRect(x, bot - lvl.toFloat() * (bot - top), x + w, bot, p)
            if (e.id == selId) {
                p.color = sub; p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.5f)
                cv.drawRect(x - dp(1f), top, x + w + dp(1f), bot, p)
                p.style = Paint.Style.FILL; p.strokeWidth = 1f
            }
        }
        cv.restore()
        if (events.isEmpty()) {
            p.color = sub; p.textSize = dp(14f)
            cv.drawText(L.t("No events"), width / 2f, height / 2f, p)
        }
    }
}

class BarChartView(c: Context) : View(c) {
    var nights: List<Pair<Int, Double>> = emptyList()
    var selected = 0
    var onPick: (Int) -> Unit = {}
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(130f).toInt())

    override fun onDraw(cv: Canvas) {
        val n = nights.size
        if (n == 0) return
        val mx = max(nights.maxOf { it.second }, 1.0)
        val slot = width / n.toFloat()
        val bot = height - dp(18f)
        val maxH = bot - dp(16f)
        p.textAlign = Paint.Align.CENTER; p.textSize = dp(10f)
        nights.forEachIndexed { i, (k, v) ->
            val x0 = slot * i + slot * .15f
            val x1 = slot * (i + 1) - slot * .15f
            val h = if (v > 0) max(dp(2f), (v / mx).toFloat() * maxH) else 0f
            p.style = Paint.Style.FILL
            p.color = if (k == selected) pri else ((pri and 0x00FFFFFF) or 0x88000000.toInt())
            if (h > 0) cv.drawRoundRect(x0, bot - h, x1, bot, dp(3f), dp(3f), p)
            p.color = if (k == selected) on else sub
            cv.drawText((k % 100).toString(), (x0 + x1) / 2, height - dp(4f), p)
            if (v > 0) cv.drawText("%.0f".format(v), (x0 + x1) / 2, bot - h - dp(3f), p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && nights.isNotEmpty()) {
            val i = (e.x / (width / nights.size.toFloat())).toInt().coerceIn(0, nights.size - 1)
            selected = nights[i].first; onPick(selected); invalidate()
        }
        return true
    }
}

class ScoreRing(c: Context) : View(c) {
    var score = -1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(112f).toInt(), dp(112f).toInt())

    override fun onDraw(cv: Canvas) {
        val s = dp(9f)
        val rect = RectF(s, s, width - s, height - s)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(10f); p.strokeCap = Paint.Cap.ROUND
        p.color = grid
        cv.drawArc(rect, -90f, 360f, false, p)
        if (score >= 0) {
            p.color = when {
                score >= 70 -> 0xFF66BB6A.toInt()
                score >= 40 -> 0xFFFFB300.toInt()
                else -> 0xFFFF7043.toInt()
            }
            cv.drawArc(rect, -90f, 360f * score / 100f, false, p)
        }
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER
        p.color = on; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = dp(30f)
        cv.drawText(if (score >= 0) score.toString() else "-", width / 2f, height / 2f + dp(6f), p)
        p.color = sub; p.typeface = Typeface.DEFAULT; p.textSize = dp(11f)
        cv.drawText(L.t("SCORE"), width / 2f, height / 2f + dp(24f), p)
    }
}


/** Audacity-style envelope: mirrored bars, height = loudness (-70..0 dBFS), dashed lines = trigger level. */
class LevelView(c: Context) : View(c) {
    var levels = FloatArray(0)
    var thr = Float.NaN
    var cursor = -1f
    var seconds = true
    var onSeek: ((Float) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lo = -70f

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(150f).toInt())

    private fun amp(db: Float) = ((db - lo) / -lo).coerceIn(0f, 1f)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val bottom = dp(18f); val h = height - bottom; val mid = h / 2
        p.style = Paint.Style.FILL; p.strokeWidth = 1f
        p.color = grid
        cv.drawLine(0f, mid, w, mid, p)
        val n = levels.size
        if (n == 0) return
        val bw = w / n
        for (i in 0 until n) {
            val a = amp(levels[i]) * mid
            val above = thr.isNaN() || levels[i] > thr
            p.color = if (above) pri else (sub and 0x00FFFFFF) or 0x99000000.toInt()
            cv.drawRect(i * bw, mid - a, i * bw + max(bw - 1f, 1.5f), mid + a, p)
        }
        if (!thr.isNaN()) {
            val a = amp(thr) * mid
            p.color = axis; p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f)
            p.pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
            cv.drawLine(0f, mid - a, w, mid - a, p); cv.drawLine(0f, mid + a, w, mid + a, p)
            p.pathEffect = null
        }
        p.style = Paint.Style.FILL; p.color = sub; p.textSize = dp(10f); p.textAlign = Paint.Align.LEFT
        val total = n / 10f
        if (seconds) {
            val step = if (total > 40) 10 else 5
            var t = 0
            while (t <= total) {
                p.textAlign = if (t == 0) Paint.Align.LEFT else Paint.Align.CENTER
                cv.drawText("${t}s", t / total * w + (if (t == 0) 0f else 0f), height - dp(4f), p)
                t += step
            }
        }
        p.textAlign = Paint.Align.RIGHT
        cv.drawText("0 dB", w, dp(10f), p)
        cv.drawText("-70", w, h - dp(2f), p)
        if (cursor >= 0f) {
            p.color = on; p.strokeWidth = dp(1.5f); p.style = Paint.Style.STROKE
            cv.drawLine(cursor * w, 0f, cursor * w, h, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cb = onSeek ?: return false
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            cb((e.x / width).coerceIn(0f, 1f)); return true
        }
        return true
    }
}
