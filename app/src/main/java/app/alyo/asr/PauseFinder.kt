package app.alyo.asr

/**
 * Loudness of the audio seen so far, one value per 20 ms, used to find the natural place to split
 * a long run of speech: the quietest moment, which is where the speaker drew breath.
 */
class PauseFinder(private val frameSamples: Int = 320) {
    private var energies = FloatArray(4096)
    private var frames = 0
    private var sum = 0f
    private var filled = 0

    fun add(samples: FloatArray) {
        for (s in samples) {
            sum += s * s
            if (++filled == frameSamples) {
                if (frames == energies.size) energies = energies.copyOf(frames * 2)
                energies[frames++] = sum / frameSamples
                sum = 0f
                filled = 0
            }
        }
    }

    /**
     * @param sample the middle of the quietest stretch
     * @param depth how quiet it is next to the speech around it: its energy over the median
     *   energy of the searched range. Near 0 is a real pause; near 1 means there was none.
     */
    data class Pause(val sample: Long, val depth: Float)

    /** The quietest [windowMs] between two sample positions, or null if the range is too short to hold one. */
    fun quietest(fromSample: Long, toSample: Long, windowMs: Int = 240): Pause? {
        val window = windowMs * 16 / frameSamples
        val first = (fromSample / frameSamples).toInt().coerceAtLeast(0)
        val last = (toSample / frameSamples).toInt().coerceAtMost(frames)
        if (last - first < window * 2) return null

        var running = 0f
        for (i in first until first + window) running += energies[i]
        var best = running
        var bestAt = first
        for (i in first + 1..last - window) {
            running += energies[i + window - 1] - energies[i - 1]
            if (running < best) {
                best = running
                bestAt = i
            }
        }
        val median = energies.copyOfRange(first, last).also { it.sort() }[(last - first) / 2]
        val depth = if (median > 0f) (best / window) / median else 1f
        return Pause((bestAt + window / 2).toLong() * frameSamples, depth)
    }
}
