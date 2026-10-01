package app.alyo.dictation

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Every speech model here expects 16 kHz mono. */
const val SAMPLE_RATE = 16_000

/** Where 16 kHz mono 16-bit samples come from. Blocking; used from the capture thread only. */
interface AudioSource {
    fun start()

    /** Blocks until samples are available. Returns the count read, or a negative value when the source is exhausted or broken. */
    fun read(buffer: ShortArray): Int
    fun release()
}

class MicSource : AudioSource {
    private val record: AudioRecord

    init {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @SuppressLint("MissingPermission") // Callers check RECORD_AUDIO before starting a dictation.
        record = AudioRecord(
            // Tuned for speech recognition: no aggressive noise gating that would clip word onsets.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            // A second of headroom: the models may still be loading while the first words arrive.
            maxOf(min, SAMPLE_RATE * 2),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
    }

    override fun start() = record.startRecording()
    override fun read(buffer: ShortArray): Int = record.read(buffer, 0, buffer.size)
    override fun release() = record.release()
}

/**
 * Plays a WAV file through the dictation pipeline at real-time speed, as if it were the
 * microphone. Test builds only: it lets the whole capture and transcribe path be exercised on a
 * device with nobody speaking into it. Accepts 16-bit mono at 16 kHz.
 */
class WavFileSource(file: File) : AudioSource {
    private val raf = RandomAccessFile(file, "r")
    private var startedAtNs = 0L
    private var delivered = 0L
    private val bytes = ByteArray(8192)

    init {
        // Walk the RIFF chunks: encoders are free to put padding or metadata before the samples.
        val word = ByteArray(8)
        raf.seek(12)
        var rate = 0
        while (true) {
            raf.readFully(word)
            val id = String(word, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(word, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") break
            if (id == "fmt ") {
                val fmt = ByteArray(size)
                raf.readFully(fmt)
                rate = ByteBuffer.wrap(fmt, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            } else {
                raf.seek(raf.filePointer + size)
            }
        }
        require(rate == SAMPLE_RATE) { "Unsupported WAV rate $rate" }
    }

    override fun start() {
        startedAtNs = System.nanoTime()
    }

    override fun read(buffer: ShortArray): Int {
        val want = minOf(buffer.size, bytes.size / 2)
        val got = raf.read(bytes, 0, want * 2)
        // After the file ends the "microphone" hears silence, so stopping after a pause can be tested too.
        val samples = if (got <= 0) want else got / 2
        if (got <= 0) buffer.fill(0, 0, samples) else ByteBuffer.wrap(bytes, 0, samples * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(buffer, 0, samples)
        delivered += samples
        val dueNs = startedAtNs + delivered * 1_000_000_000L / SAMPLE_RATE
        val waitMs = (dueNs - System.nanoTime()) / 1_000_000
        if (waitMs > 0) Thread.sleep(waitMs)
        return samples
    }

    override fun release() = raf.close()
}
