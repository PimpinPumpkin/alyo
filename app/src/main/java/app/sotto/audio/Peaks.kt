package app.sotto.audio

import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * The waveform: one loudness value per 20 ms, 0..255 on a decibel scale so quiet speech still
 * draws a visible bar. Growable while recording, and safe to read from the UI thread while the
 * audio thread appends.
 */
class Peaks(initial: ByteArray = ByteArray(0)) {
    @Volatile private var data: ByteArray = if (initial.isEmpty()) ByteArray(4096) else initial
    @Volatile var size: Int = initial.size
        private set

    private var windowMax = 0
    private var windowCount = 0

    // How many bars sit at each stored level, for finding this recording's own floor and ceiling.
    private val histogram = IntArray(256).also { counts -> for (i in 0 until initial.size) counts[initial[i].toInt() and 0xFF]++ }
    private var boundsAt = -1
    private var floor = 0f
    private var span = 1f

    /**
     * Loudness of bar [index] relative to this recording: 0 at its background noise, 1 at its
     * loud speech. Absolute level is a poor guide to what is speech, because a phone microphone in
     * an ordinary room already reads well above silence; what matters is how far a moment stands
     * out from the room it was recorded in.
     */
    fun relative(index: Int): Float {
        if (index !in 0 until size) return 0f
        if (boundsAt != size) measure()
        return ((level(index) - floor) / span).coerceIn(0f, 1f)
    }

    private fun measure() {
        val total = size
        fun percentile(fraction: Float): Float {
            var seen = 0
            for (level in 0..255) {
                seen += histogram[level]
                if (seen >= total * fraction) return level / 255f
            }
            return 1f
        }
        // Loud speech is the 97th percentile, but never below about -30 dB, so a recording of
        // nothing but room tone is not stretched up to look like speech.
        val ceiling = maxOf(percentile(0.97f), MIN_CEILING)
        // The room is the quiet fifth of the recording, but at least 16 dB under the ceiling, so
        // wall-to-wall talking does not make its own quieter words vanish.
        floor = minOf(percentile(0.2f), ceiling - MIN_SPAN)
        span = ceiling - floor
        boundsAt = total
    }

    /** Level of bar [index] as 0..1, or 0 outside the recorded range. */
    fun level(index: Int): Float = if (index in 0 until size) (data[index].toInt() and 0xFF) / 255f else 0f

    /** Consumes captured samples; returns how many new bars were completed. */
    fun add(pcm: ShortArray, count: Int): Int {
        var added = 0
        for (i in 0 until count) {
            val v = abs(pcm[i].toInt())
            if (v > windowMax) windowMax = v
            if (++windowCount == AudioSpec.PEAK_SAMPLES) {
                append(encode(windowMax))
                windowMax = 0
                windowCount = 0
                added++
            }
        }
        return added
    }

    private fun append(value: Byte) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size] = value
        histogram[value.toInt() and 0xFF]++
        size++
    }

    fun toByteArray(from: Int = 0, to: Int = size): ByteArray = data.copyOfRange(from.coerceIn(0, size), to.coerceIn(0, size))

    /** Keeps only the bars inside [keep], mirroring [M4a.copyRanges]. */
    fun slice(keep: List<Span>): Peaks {
        var out = ByteArray(0)
        for (span in keep) {
            out += toByteArray((span.startMs / AudioSpec.PEAK_MS).toInt(), (span.endMs / AudioSpec.PEAK_MS).toInt())
        }
        return Peaks(out)
    }

    fun save(file: File) = file.writeBytes(toByteArray())

    companion object {
        private const val FLOOR_DB = -54f
        private const val MIN_CEILING = 0.45f
        private const val MIN_SPAN = 0.3f

        fun load(file: File): Peaks = if (file.exists()) Peaks(file.readBytes()) else Peaks()

        private fun encode(peak: Int): Byte {
            if (peak <= 0) return 0
            val db = 20f * log10(peak / 32768f)
            return (((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        }
    }
}
