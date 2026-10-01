package app.sotto.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteOrder

/**
 * Any audio file the phone can decode, delivered the way the microphone delivers sound: 48 kHz
 * mono 16-bit, pulled a block at a time. It is what lets an imported file, or one of the app's
 * own recordings, go through exactly the pipeline a live recording does.
 */
class AudioDecoder private constructor(private val extractor: MediaExtractor) : PcmSource {
    constructor(file: File) : this(MediaExtractor().apply { setDataSource(file.path) })
    constructor(context: Context, uri: Uri) : this(MediaExtractor().apply { setDataSource(context, uri, null) })

    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    val durationMs: Long
    private var rate: Int
    private var channels: Int
    private var inputDone = false
    private var outputDone = false
    private var positionUs = 0L

    // Decoded, mixed to mono and resampled, waiting to be read.
    private var pending = ShortArray(0)
    private var pendingAt = 0
    // Linear resampler state: where the next output sample falls between two input samples.
    private var phase = 0.0
    private var previous = 0f

    init {
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("No audio in this file")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
        rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /** How far through the file, 0 to 1. */
    val progress: Float get() = if (durationMs > 0) (positionUs / 1000f / durationMs).coerceIn(0f, 1f) else 0f

    override fun start() = Unit
    override fun stop() = Unit

    override fun read(buffer: ShortArray): Int {
        while (pendingAt >= pending.size) {
            if (outputDone) return -1
            decodeMore()
        }
        val n = minOf(buffer.size, pending.size - pendingAt)
        pending.copyInto(buffer, 0, pendingAt, pendingAt + n)
        pendingAt += n
        return n
    }

    private fun decodeMore() {
        if (!inputDone) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                if (size < 0) {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                // What the decoder actually produces can differ from what the container claimed.
                rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
            else -> if (index >= 0) {
                if (info.size > 0) {
                    val shorts = codec.getOutputBuffer(index)!!.apply { position(info.offset).limit(info.offset + info.size) }
                        .order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = shorts.remaining() / channels
                    val mono = FloatArray(frames) { f ->
                        var sum = 0f
                        for (c in 0 until channels) sum += shorts.get(f * channels + c)
                        sum / channels
                    }
                    pending = resample(mono)
                    pendingAt = 0
                    positionUs = info.presentationTimeUs
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                codec.releaseOutputBuffer(index, false)
            }
        }
    }

    private fun resample(input: FloatArray): ShortArray {
        if (rate == AudioSpec.SAMPLE_RATE) return ShortArray(input.size) { input[it].toInt().toShort() }
        val step = rate.toDouble() / AudioSpec.SAMPLE_RATE
        val out = ShortArray(((input.size - phase) / step).toInt() + 2)
        var n = 0
        // phase is measured from `previous`, the last sample of the block before this one.
        while (phase < input.size) {
            val i = phase.toInt()
            val a = if (i == 0) previous else input[i - 1]
            val b = input[i]
            out[n++] = (a + (b - a) * (phase - i).toFloat()).toInt().coerceIn(-32768, 32767).toShort()
            phase += step
        }
        phase -= input.size
        if (input.isNotEmpty()) previous = input.last()
        return if (n == out.size) out else out.copyOf(n)
    }

    override fun release() {
        runCatching { codec.stop() }
        codec.release()
        extractor.release()
    }
}
