package app.snorelog

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder
import kotlin.math.min

/** Hardware AAC-LC encoder (24 kbps mono) -> .m4a. Same write()/close() API the service used for WAV. */
class AacWriter(f: File, private val sr: Int) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var started = false
    private var ptsUs = 0L

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 16000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun write(s: ShortArray, off: Int, len: Int) {
        var i = off
        var stalls = 0
        val end = off + len
        while (i < end) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0 && ++stalls > 500) throw java.io.IOException("encoder stalled")
            if (idx >= 0) {
                val ib = codec.getInputBuffer(idx)!!
                ib.clear(); ib.order(ByteOrder.LITTLE_ENDIAN)
                val n = min(end - i, ib.capacity() / 2)
                for (k in 0 until n) ib.putShort(s[i + k])
                codec.queueInputBuffer(idx, 0, n * 2, ptsUs, 0)
                ptsUs += n * 1_000_000L / sr
                i += n
            }
            drain(false)
        }
    }

    private fun drain(eos: Boolean) {
        var waits = 0
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                }
                o >= 0 -> {
                    val ob = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos || ++waits > 100) return
            }
        }
    }

    fun close() {
        try {
            val idx = codec.dequeueInputBuffer(100_000)
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } catch (t: Throwable) { }
        try { codec.stop() } catch (t: Throwable) { }
        try { codec.release() } catch (t: Throwable) { }
        try { if (started) muxer.stop() } catch (t: Throwable) { }
        try { muxer.release() } catch (t: Throwable) { }
    }
}
