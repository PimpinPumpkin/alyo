package app.sotto.audio

/** The fixed shape of everything Sotto records. */
object AudioSpec {
    /** Capture and storage rate. 48 kHz keeps recordings pleasant to listen to, not just transcribable. */
    const val SAMPLE_RATE = 48_000

    /** Every speech model here expects 16 kHz; capture is decimated by exactly 3 to get it. */
    const val ASR_SAMPLE_RATE = 16_000
    const val DECIMATION = SAMPLE_RATE / ASR_SAMPLE_RATE

    const val AAC_BITRATE = 96_000
    const val AAC_FRAME_SAMPLES = 1024

    /** One waveform bar per 20 ms of audio. */
    const val PEAK_SAMPLES = 960
    const val PEAKS_PER_SECOND = SAMPLE_RATE / PEAK_SAMPLES
    const val PEAK_MS = 1000 / PEAKS_PER_SECOND

    fun samplesToUs(samples: Long): Long = samples * 1_000_000L / SAMPLE_RATE
    fun samplesToMs(samples: Long): Long = samples * 1000L / SAMPLE_RATE
}

/** A stretch of a recording's timeline, start inclusive and end exclusive, in milliseconds. */
data class Span(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
    operator fun contains(ms: Long): Boolean = ms in startMs until endMs
}
