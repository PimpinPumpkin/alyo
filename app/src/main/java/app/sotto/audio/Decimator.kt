package app.sotto.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Streaming 48 kHz -> 16 kHz converter for the speech models: a windowed-sinc low-pass at 7.2 kHz
 * followed by keeping every third sample. Without the filter, everything above 8 kHz (sibilants,
 * room hiss) folds back into the speech band and costs recognition accuracy.
 */
class Decimator(private val factor: Int = AudioSpec.DECIMATION) {
    private val taps: FloatArray = design(TAPS, cutoff = 0.45f / factor)
    private val history = FloatArray(TAPS)
    private var pos = 0
    private var phase = 0

    /** Feeds [count] 16-bit samples and returns the decimated block as floats in [-1, 1]. */
    fun process(input: ShortArray, count: Int): FloatArray {
        val out = FloatArray((count + phase) / factor)
        var o = 0
        for (i in 0 until count) {
            history[pos] = input[i] / 32768f
            pos = (pos + 1) % TAPS
            if (++phase == factor) {
                phase = 0
                var acc = 0f
                var h = pos
                for (t in 0 until TAPS) {
                    acc += taps[t] * history[h]
                    if (++h == TAPS) h = 0
                }
                out[o++] = acc
            }
        }
        return if (o == out.size) out else out.copyOf(o)
    }

    private companion object {
        const val TAPS = 63

        /** Hamming-windowed sinc, normalised to unity gain at DC. [cutoff] is in cycles per input sample. */
        fun design(n: Int, cutoff: Float): FloatArray {
            val mid = (n - 1) / 2.0
            val h = DoubleArray(n) { i ->
                val x = i - mid
                val sinc = if (x == 0.0) 2.0 * cutoff else sin(2.0 * PI * cutoff * x) / (PI * x)
                sinc * (0.54 - 0.46 * cos(2.0 * PI * i / (n - 1)))
            }
            val sum = h.sum()
            return FloatArray(n) { (h[it] / sum).toFloat() }
        }
    }
}
