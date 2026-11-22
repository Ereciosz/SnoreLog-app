package app.snorelog

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/** YAMNet (TFLite) wrapper. If the model or the native library is missing, ok == false and callers skip it. */
class Yamnet(ctx: Context) {
    private var interp: Interpreter? = null
    private var idx = 38
    private var outN = 521
    private var fartIdx = -1
    /** Highest "Fart" score over the windows of the last snoreProb call. */
    @Volatile var lastFart = 0f
    val ok: Boolean get() = interp != null

    init {
        try {
            val bytes = ctx.assets.open("yamnet.tflite").use { it.readBytes() }
            val bb = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            bb.put(bytes); bb.rewind()
            val ip = Interpreter(bb)
            outN = ip.getOutputTensor(0).shape().fold(1) { a, b -> a * b }
            try {
                ctx.assets.open("yamnet_class_map.csv").bufferedReader().useLines { ls ->
                    ls.forEach { l ->
                        if (l.endsWith(",Snoring")) idx = l.substringBefore(',').toInt()
                        if (l.endsWith(",Fart")) fartIdx = l.substringBefore(',').toInt()
                    }
                }
            } catch (e: Exception) { /* keep default index */ }
            interp = ip
        } catch (t: Throwable) {
            interp = null
        }
    }

    /** Highest "Snoring" score over consecutive 0.975 s windows. Returns 1 (keep) if inference fails. */
    fun snoreProb(x: ShortArray, len: Int): Float {
        val ip = interp ?: return 1f
        return try {
            val win = 15600
            val inp = ByteBuffer.allocateDirect(win * 4).order(ByteOrder.nativeOrder())
            val out = ByteBuffer.allocateDirect(outN * 4).order(ByteOrder.nativeOrder())
            var best = 0f
            var fb2 = 0f
            var start = 0
            while (start == 0 || start + win <= len) {
                inp.rewind()
                for (i in 0 until win) inp.putFloat(if (start + i < len) x[start + i] / 32768f else 0f)
                inp.rewind(); out.rewind()
                ip.run(inp, out)
                out.rewind()
                val fb = out.asFloatBuffer()
                best = max(best, fb.get(idx))
                if (fartIdx in 0 until outN) fb2 = max(fb2, fb.get(fartIdx))
                start += win
            }
            lastFart = fb2
            best
        } catch (t: Throwable) { 1f }
    }

    fun close() { try { interp?.close() } catch (t: Throwable) {} }
}
