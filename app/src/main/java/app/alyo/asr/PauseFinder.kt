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

    /** The energy above which a 20 ms frame counts as sound: halfway, as ratios go, between the room's hush and the speech so far. Null while there is too little to tell. */
    private fun loudness(): Float? {
        if (frames < 50) return null
        val sorted = energies.copyOfRange(0, frames).also { it.sort() }
        val speech = sorted[frames * 9 / 10]
        // Digital silence, as a file can have, would put the line at nothing: 40 dB under speech is quiet enough.
        val hush = maxOf(sorted[frames / 10], speech * 1e-4f)
        return if (speech < hush * 10f) null else kotlin.math.sqrt(hush.toDouble() * speech).toFloat()
    }

    /**
     * Where to end a sentence that paused somewhere between [afterSample] and [heardSample]: just
     * before the sound that follows the pause, or [heardSample] if the pause is still going on
     * there. Sound up to [fedSample] is looked at, which is past what the recognizer has taken in.
     *
     * A recognizer reports a pause late, and by then the next word may be under way or about to
     * be. Cutting where the recognizer happens to be can cut that word in two; cutting inside the
     * pause never does.
     */
    fun cutInPause(afterSample: Long, heardSample: Long, fedSample: Long): Long {
        val loud = loudness() ?: return heardSample
        val first = (afterSample / frameSamples).toInt().coerceAtLeast(0)
        val heard = (heardSample / frameSamples).toInt().coerceAtMost(frames)
        val last = (fedSample / frameSamples).toInt().coerceAtMost(frames)
        // The longest quiet stretch that starts before the recognizer's position.
        var bestStart = -1
        var bestEnd = -1
        var i = first
        while (i < heard) {
            if (energies[i] > loud) {
                i++
                continue
            }
            var end = i
            while (end < last && energies[end] <= loud) end++
            if (end - i > bestEnd - bestStart) {
                bestStart = i
                bestEnd = end
            }
            i = end
        }
        // Under 200 ms is a gap between words, not a pause.
        if (bestEnd - bestStart < 10) return heardSample
        // Sound picks up again at bestEnd, unless the quiet runs to the end of what has come in.
        val cut = if (bestEnd < last) bestEnd - LEAD_FRAMES else heard
        return cut.coerceIn(bestStart, heard).toLong() * frameSamples
    }

    /** How much sound, in milliseconds, there is between two sample positions. */
    fun soundMs(fromSample: Long, toSample: Long): Int {
        val loud = loudness() ?: return 0
        val first = (fromSample / frameSamples).toInt().coerceAtLeast(0)
        val last = (toSample / frameSamples).toInt().coerceAtMost(frames)
        return (first until last).count { energies[it] > loud } * frameSamples / 16
    }

    private companion object {
        // 120 ms of the quiet before a word is kept with it: its first sound is often soft.
        const val LEAD_FRAMES = 6
    }
}
