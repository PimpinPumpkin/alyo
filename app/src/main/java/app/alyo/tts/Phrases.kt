package app.alyo.tts

/**
 * Cuts text into the pieces it is spoken in. A voice makes a whole piece before any of it can be
 * heard, so the first sound comes sooner the shorter the first piece is. The cuts are where a
 * reader pauses anyway: after a sentence, and after a comma, semicolon, colon or dash that is
 * followed by a space.
 */
object Phrases {
    /** A piece shorter than this is spoken with the one after it: "Yes," alone sounds clipped. */
    private const val MIN_LENGTH = 12

    fun split(text: String): List<String> {
        val pieces = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            current.append(c)
            val next = text.getOrNull(i + 1)
            // "1,000" and "3:45" are not pauses: a pause has a space after it.
            val pause = c in PAUSES && (next == null || next.isWhitespace())
            if (pause && current.trim().length >= MIN_LENGTH) {
                pieces += current.toString().trim()
                current.clear()
            }
            i++
        }
        val rest = current.toString().trim()
        if (rest.isNotEmpty()) {
            // A short tail joins the piece before it, for the same reason a short start waits.
            if (rest.length < MIN_LENGTH && pieces.isNotEmpty() && !pieces.last().last().isSentenceEnd()) {
                pieces[pieces.lastIndex] = pieces.last() + " " + rest
            } else {
                pieces += rest
            }
        }
        return pieces
    }

    private fun Char.isSentenceEnd() = this in ".!?。！？"

    // The two escapes are the long dashes.
    private const val PAUSES = ",;:.!?\u2014\u2013，；：。！？"

    /**
     * Cuts the silence a voice leaves around what it says down to [leadMs] before and [tailMs]
     * after. Some voices pad every piece with half a second on each side, which is a wait before
     * the first word and a gap at every pause once the text is made in pieces.
     */
    fun trimSilence(samples: FloatArray, sampleRate: Int, leadMs: Int = 30, tailMs: Int = 160): FloatArray {
        val first = samples.indexOfFirst { it > QUIET || it < -QUIET }
        if (first < 0) return samples
        val last = samples.indexOfLast { it > QUIET || it < -QUIET }
        val from = (first - sampleRate * leadMs / 1000).coerceAtLeast(0)
        val to = (last + 1 + sampleRate * tailMs / 1000).coerceAtMost(samples.size)
        return if (from == 0 && to == samples.size) samples else samples.copyOfRange(from, to)
    }

    /** Below this, on a scale where 1 is as loud as it gets, a sample counts as silence. */
    private const val QUIET = 0.02f
}
