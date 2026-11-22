package app.snorelog

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Hidden: tap the score ring 7 times. A sleeping moon with floating Zs and a synthesised snore. */
object Egg {
    private var taps = 0
    private var last = 0L

    fun tap(a: Activity) {
        val now = System.currentTimeMillis()
        taps = if (now - last > 1500) 1 else taps + 1
        last = now
        if (taps >= 7) { taps = 0; show(a) }
    }

    private const val CYCLE = 3.0   // seconds: inhale 0-1, rest, snort 1.5-2.5, rest

    fun breath(t: Double): Double {
        val c = t % CYCLE
        return when {
            c < 1.0 -> sin(PI * c).pow(2.0)
            c in 1.5..2.5 -> sin(PI * (c - 1.5)).pow(2.0)
            else -> 0.0
        }
    }

    private fun show(a: Activity) {
        val v = EggView(a)
        val dp = a.resources.displayMetrics.density
        v.minimumHeight = (280 * dp).toInt()
        val track = if (Settings(a).armed) null else sound()   // never play while recording: it would be saved as a snore
        val anim = ValueAnimator.ofFloat(0f, 6f).apply {
            duration = 6000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { v.t = (it.animatedValue as Float).toDouble(); v.invalidate() }
            start()
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
            .setView(v)
            .setPositiveButton("Shh") { d, _ -> d.dismiss() }
            .setOnDismissListener { anim.cancel(); try { track?.stop(); track?.release() } catch (_: Throwable) {} }
            .show()
        try { track?.play() } catch (_: Throwable) {}
    }

    private fun sound(): AudioTrack? = try {
        val sr = 16000; val n = sr * 6
        val d = ShortArray(n)
        var lp = 0.0; val rnd = java.util.Random(7)
        for (i in 0 until n) {
            val t = (i % (sr * 3)) / sr.toDouble()
            val env = breath(t.toDouble())
            val f = if (t < 1.0) 110 + 30 * t else 140 - 30 * (t - 1.5)
            val saw = ((t * f) % 1.0) * 2 - 1
            val flutter = 0.6 + 0.4 * sin(2 * PI * 28 * t)
            lp += (rnd.nextGaussian() * 0.6 - lp) * 0.15
            val s = env * flutter * (0.5 * saw + 0.5 * lp.coerceIn(-1.0, 1.0))
            d[i] = (s * 0.6 * 32767).toInt().toShort()
        }
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(n * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        at.write(d, 0, n)
        at.setLoopPoints(0, n, -1)
        at
    } catch (_: Throwable) { null }
}

class EggView(c: Context) : View(c) {
    var t = 0.0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h * 0.58f; val r = min(w, h) * 0.28f
        val b = Egg.breath(t).toFloat()
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#0F1730"); cv.drawRoundRect(RectF(0f, 0f, w, h), 24f, 24f, p)
        // twinkling stars
        for (i in 0 until 14) {
            val sx = ((i * 7919) % 97) / 97f * w; val sy = ((i * 104729) % 89) / 89f * h * 0.8f
            p.color = Color.WHITE; p.alpha = (110 + 100 * sin(t * 2 + i)).toInt().coerceIn(30, 255)
            cv.drawCircle(sx, sy, 2.5f + (i % 3), p)
        }
        p.alpha = 255
        // moon, breathing
        val rr = r * (1f + 0.04f * b)
        p.color = Color.parseColor("#FFD54F"); cv.drawCircle(cx, cy, rr, p)
        p.color = Color.parseColor("#F5C23A")
        cv.drawCircle(cx - rr * 0.35f, cy + rr * 0.35f, rr * 0.16f, p)
        cv.drawCircle(cx + rr * 0.45f, cy - rr * 0.3f, rr * 0.10f, p)
        // closed eyes
        p.style = Paint.Style.STROKE; p.strokeWidth = max(4f, rr * 0.06f); p.strokeCap = Paint.Cap.ROUND
        p.color = Color.parseColor("#5D4037")
        for (sx in floatArrayOf(-0.38f, 0.38f)) {
            val ex = cx + rr * sx; val ey = cy - rr * 0.12f
            cv.drawArc(RectF(ex - rr * 0.2f, ey - rr * 0.1f, ex + rr * 0.2f, ey + rr * 0.1f), 0f, 180f, false, p)
        }
        // mouth: opens with the breath
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#5D4037")
        val mh = rr * (0.06f + 0.26f * b); val mw = rr * (0.22f + 0.1f * b)
        cv.drawOval(RectF(cx - mw, cy + rr * 0.38f - mh, cx + mw, cy + rr * 0.38f + mh), p)
        // floating Zs
        p.color = Color.WHITE
        for (i in 0 until 3) {
            val ph = ((t * 0.4 + i / 3.0) % 1.0).toFloat()
            p.alpha = ((1f - ph) * 255).toInt()
            p.textSize = rr * (0.28f + 0.4f * ph); p.isFakeBoldText = true
            cv.drawText("Z", cx + rr * 0.75f + ph * rr * 0.5f, cy - rr * 0.55f - ph * rr * 1.1f, p)
        }
        p.alpha = 255; p.isFakeBoldText = false
    }
}
