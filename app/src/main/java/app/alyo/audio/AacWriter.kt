package app.alyo.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes PCM to AAC as it is captured and appends it to an ADTS (`.aac`) file.
 *
 * ADTS is deliberate: every frame carries its own header, so the file is playable up to the last
 * frame written even if the process dies mid-recording. An MP4 needs an index written at the very
 * end and is unreadable without it. The seekable `.m4a` the rest of the app uses is produced from
 * this stream by [M4a.fromAdts] once recording stops (or on the next launch after a crash).
 */
class AacWriter(file: File) {
    private val out = BufferedOutputStream(FileOutputStream(file, false), 64 * 1024)
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private val header = ByteArray(7)
    private var frame = ByteArray(2048)
    private var samplesIn = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioSpec.SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AudioSpec.AAC_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun write(pcm: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)!!
                buffer.clear()
                val n = minOf(count - offset, buffer.remaining() / 2)
                buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, offset, n)
                codec.queueInputBuffer(index, 0, n * 2, AudioSpec.samplesToUs(samplesIn), 0)
                samplesIn += n
                offset += n
            }
            drain(endOfStream = false)
        }
    }

    /** Flushes the encoder and closes the file. The writer cannot be used afterwards. */
    fun close() {
        try {
            while (true) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, AudioSpec.samplesToUs(samplesIn), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(endOfStream = false)
            }
            drain(endOfStream = true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            out.close()
        }
    }

    /** Pushes buffered frames to disk so a crash loses at most the last second or so. */
    fun flush() = out.flush()

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return
                continue
            }
            if (index < 0) continue
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (info.size > 0 && !isConfig) {
                val buffer: ByteBuffer = codec.getOutputBuffer(index)!!
                buffer.position(info.offset).limit(info.offset + info.size)
                if (frame.size < info.size) frame = ByteArray(info.size)
                buffer.get(frame, 0, info.size)
                Adts.fillHeader(header, info.size)
                out.write(header)
                out.write(frame, 0, info.size)
            }
            val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            codec.releaseOutputBuffer(index, false)
            if (done) return
        }
    }
}

/** The 7-byte header that precedes every AAC frame in an ADTS stream. */
object Adts {
    const val HEADER_SIZE = 7
    private const val PROFILE_LC = 2
    private const val FREQ_INDEX_48K = 3
    private const val CHANNELS = 1

    /** AudioSpecificConfig for AAC-LC / 48 kHz / mono: what an MP4 needs to describe this stream. */
    val AUDIO_SPECIFIC_CONFIG = byteArrayOf(0x11, 0x88.toByte())

    fun fillHeader(h: ByteArray, payloadSize: Int) {
        val length = payloadSize + HEADER_SIZE
        h[0] = 0xFF.toByte()
        h[1] = 0xF1.toByte() // MPEG-4, no CRC
        h[2] = (((PROFILE_LC - 1) shl 6) or (FREQ_INDEX_48K shl 2) or (CHANNELS shr 2)).toByte()
        h[3] = (((CHANNELS and 3) shl 6) or (length shr 11)).toByte()
        h[4] = ((length shr 3) and 0xFF).toByte()
        h[5] = (((length and 7) shl 5) or 0x1F).toByte()
        h[6] = 0xFC.toByte()
    }

    /** Frame length (header included) from a header, or -1 if [h] does not start with an ADTS sync word. */
    fun frameLength(h: ByteArray): Int {
        if (h[0] != 0xFF.toByte() || (h[1].toInt() and 0xF6) != 0xF0) return -1
        return ((h[3].toInt() and 0x03) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xE0) shr 5)
    }
}
