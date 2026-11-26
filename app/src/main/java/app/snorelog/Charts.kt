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
    fun heat(t: Double, base: Int): Int {
        val lo = if (Look.contrast) 190 else if (Look.oled) 110 else 70
        return Color.argb((lo + (255 - lo) * t.coerceIn(0.0, 1.0)).toInt(), Color.red(base), Color.green(base), Color.blue(base))
    }
}

private fun View.dp(v: Float) = v * resources.displayMetrics.density
private fun View.attr(a: Int) = MaterialColors.getColor(this, a, Color.GRAY)
private val View.on get() = attr(com.google.android.material.R.attr.colorOnSurface)
private val View.sub get() = attr(com.google.android.material.R.attr.colorOnSurfaceVariant)
private val View.pri get() = attr(com.google.android.material.R.attr.colorPrimary)
private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
private val View.grid get() = withAlpha(on, if (Look.contrast) 0x99 else if (Look.oled) 0x55 else 0x33)
private val View.axis get() = withAlpha(on, if (Look.contrast) 0xFF else if (Look.oled) 0x99 else 0x55)
private val pauseColor get() = when {
    Look.contrast -> if (Look.night) 0xCC00E5FF.toInt() else 0xCC006064.toInt()
    Look.oled -> 0x9900BCD4.toInt()
    else -> 0x5500BCD4
}

private val pauseCap get() = when {
    Look.contrast -> if (Look.night) 0xFF00E5FF.toInt() else 0xFF006064.toInt()
    else -> 0xFF00BCD4.toInt()
}

class CalendarView(c: Context) : View(c) {
    var month: Calendar = Calendar.getInstance()
    var data: Map<Int, Double> = emptyMap()
    var selected = 0
    var fixedMax = 0.0 // > 0: colour scale 0..fixedMax (score mode), otherwise relative to the busiest night
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
        val mx = if (fixedMax > 0) fixedMax else max(data.values.maxOrNull() ?: 1.0, 1.0)
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

    /** Width of one chart column for the visible span: about 96 columns across. */
    private fun bucketMs(span: Long): Long {
        val steps = longArrayOf(5_000L, 10_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1800_000L)
        return steps.firstOrNull { span / it <= 96 } ?: steps.last()
    }

    /** Zooms to a moment, e.g. a suspected pause, with a few minutes of context around it. */
    fun focus(ts: Long, durMs: Long) {
        val mid = ts + durMs / 2
        setView(mid - 3 * 60_000L, mid + 3 * 60_000L)
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
        // Confirmed single tap only: a double tap zooms and must not also play a clip.
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val (lo, hi) = range()
            // Double on purpose: epoch milliseconds do not fit a Float's precision.
            val span = (hi - lo).toDouble()
            val tt = lo + (e.x - PL).toDouble() / (PR - PL) * span
            val tol = dp(14f).toDouble() / (PR - PL) * span
            // The column under the finger wins (its loudest event), so every drawn bar is tappable.
            val bm = bucketMs(span.toLong())
            val bs = tt.toLong() / bm * bm
            val best = events.filter { it.ts < bs + bm && it.ts + it.durMs > bs }.maxByOrNull { it.peak }
                ?: events.filter { tt >= it.ts - tol && tt <= it.ts + it.durMs + tol }
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
        fmtHm.timeZone = java.util.TimeZone.getDefault()
        val (lo, hi) = range()
        val base = Night.cal(key).timeInMillis
        val l = PL; val r = PR; val top = dp(8f); val bot = height - dp(22f)
        val span = (hi - lo).toFloat()
        val steps = longArrayOf(5 * 60_000L, 10 * 60_000L, 30 * 60_000L, hr, 2 * hr, 3 * hr)
        val step = steps.firstOrNull { span / it <= 5 } ?: steps.last()
        val fmt = fmtHm
        // Tonal plot surface with rounded corners, as in Material 3 containers.
        val plot = RectF(l, top, r, bot)
        p.style = Paint.Style.FILL; p.color = withAlpha(on, if (Look.contrast) 0x22 else if (Look.oled) 0x14 else 0x0F)
        cv.drawRoundRect(plot, dp(12f), dp(12f), p)
        p.textSize = dp(11f); p.textAlign = Paint.Align.CENTER; p.strokeWidth = 1f
        val clip = Path().apply { addRoundRect(plot, dp(12f), dp(12f), Path.Direction.CW) }
        cv.save(); cv.clipPath(clip)
        // Guide at 50 % of the bucket
        p.style = Paint.Style.STROKE; p.color = grid
        cv.drawLine(l, (top + bot) / 2, r, (top + bot) / 2, p)
        var t = base + ((lo - base + step - 1) / step) * step
        while (t <= hi) {
            val x = l + (t - lo) / span * (r - l)
            p.style = Paint.Style.STROKE; p.color = grid; cv.drawLine(x, top, x, bot, p)
            t += step
        }
        p.style = Paint.Style.FILL
        // Suspected pauses: a tinted band with a solid cap, so even a short one stays visible.
        for ((pt, pd) in pauses) {
            val x = l + (pt - lo) / span * (r - l)
            val w = max(dp(4f), (pd * 1000 / span).toFloat() * (r - l))
            p.color = pauseColor; cv.drawRect(x, top, x + w, bot, p)
            p.color = pauseCap; cv.drawRoundRect(x, top, x + w, top + dp(4f), dp(2f), dp(2f), p)
        }
        // Fixed time buckets: height = loudest peak in it (scaled to the tallest visible), opacity = share of the bucket spent snoring.
        val bm = bucketMs(span.toLong())
        val b0 = lo / bm * bm
        val n = ((hi - b0) / bm + 1).toInt()
        val sn = LongArray(n + 1); val pk = DoubleArray(n + 1)
        for (e in events) {
            if (e.ts + e.durMs < lo || e.ts > hi) continue
            val lvl = ((e.peak + 60) / 60).coerceIn(0.08, 1.0)
            var i = max(0, ((e.ts - b0) / bm).toInt())
            val iEnd = min(n, ((e.ts + e.durMs - b0) / bm).toInt())
            while (i <= iEnd) {
                val s0 = b0 + i * bm
                val ov = min(e.ts + e.durMs, s0 + bm) - max(e.ts, s0)
                if (ov > 0) { sn[i] += ov; pk[i] = max(pk[i], lvl) }
                i++
            }
        }
        val maxPk = max(pk.max(), 0.08)
        for (i in 0..n) {
            if (sn[i] <= 0) continue
            val bs = b0 + i * bm
            val x0 = l + (bs - lo) / span * (r - l)
            val x1 = l + (bs + bm - lo) / span * (r - l)
            val gap = min(dp(2f), (x1 - x0) * 0.2f)
            val frac = (pk[i] / maxPk).toFloat().coerceIn(0.06f, 1f)
            val share = (sn[i].toDouble() / bm).coerceIn(0.05, 1.0)
            val rad = min(dp(3f), (x1 - x0 - gap) / 2)
            p.style = Paint.Style.FILL; p.color = Night.heat(share, pri)
            cv.drawRoundRect(x0 + gap / 2, bot - frac * (bot - top), x1 - gap / 2, bot + rad, rad, rad, p)
        }
        events.firstOrNull { it.id == selId }?.let { e ->
            val x0 = l + ((e.ts - b0) / bm * bm + b0 - e.ts + e.ts - lo) / span * (r - l)
            val x1 = l + ((e.ts + e.durMs - b0) / bm * bm + b0 + bm - lo) / span * (r - l)
            p.color = on; p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.5f)
            cv.drawRoundRect(x0, top + dp(1f), max(x1, x0 + dp(4f)), bot, dp(3f), dp(3f), p)
            p.style = Paint.Style.FILL; p.strokeWidth = 1f
        }
        cv.restore()
        // Time labels under the plot
        p.style = Paint.Style.FILL; p.color = sub; p.textAlign = Paint.Align.CENTER
        t = base + ((lo - base + step - 1) / step) * step
        while (t <= hi) {
            val x = l + (t - lo) / span * (r - l)
            cv.drawText(fmt.format(Date(t)), x.coerceIn(l + dp(16f), r - dp(16f)), height - dp(5f), p)
            t += step
        }
        if (events.isEmpty()) {
            p.color = sub; p.textSize = dp(14f)
            cv.drawText(L.t("No events"), width / 2f, height / 2f, p)
        }
    }
}

class BarChartView(c: Context) : View(c) {
    var nights: List<Pair<Int, Double>> = emptyList()
    var selected = 0
    var fixedMax = 0.0 // > 0: fixed scale 0..fixedMax (score mode)
    var onPick: (Int) -> Unit = {}
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(130f).toInt())

    override fun onDraw(cv: Canvas) {
        val n = nights.size
        if (n == 0) return
        val mx = if (fixedMax > 0) fixedMax else max(nights.maxOf { it.second }, 1.0)
        val slot = width / n.toFloat()
        val bot = height - dp(18f)
        val maxH = bot - dp(16f)
        p.textAlign = Paint.Align.CENTER; p.textSize = dp(10f)
        nights.forEachIndexed { i, (k, v) ->
            val x0 = slot * i + slot * .15f
            val x1 = slot * (i + 1) - slot * .15f
            val h = if (v > 0) max(dp(2f), (v / mx).toFloat() * maxH) else 0f
            p.style = Paint.Style.FILL
            p.color = if (k == selected) pri else withAlpha(pri, if (Look.contrast) 0x99 else if (Look.oled) 0xBB else 0x88)
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
            val darkInk = Look.contrast && !Look.night
            p.color = when {
                score >= 70 -> if (darkInk) 0xFF1B5E20.toInt() else 0xFF66BB6A.toInt()
                score >= 40 -> if (darkInk) 0xFF8A5A00.toInt() else 0xFFFFB300.toInt()
                else -> if (darkInk) 0xFFBF360C.toInt() else 0xFFFF7043.toInt()
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
            p.color = if (above) pri else withAlpha(sub, if (Look.contrast) 0xCC else 0x99)
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
