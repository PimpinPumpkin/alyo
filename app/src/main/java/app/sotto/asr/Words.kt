package app.sotto.asr

import app.sotto.data.Word

/**
 * Turns a recognizer's sub-word tokens into timed words.
 *
 * Tokens are pieces like `" con"`, `"se"`, `"qu"`, `"ence"`, `","`: a leading space (or the
 * SentencePiece `▁`) opens a new word and everything else attaches to the word in progress, which
 * is also how punctuation ends up glued to the word before it.
 *
 * @param times start of each token in seconds, relative to the audio the recognizer was given
 * @param durations length of each token in seconds when the model reports it, else null
 * @param offsetMs where that audio begins on the recording's timeline
 * @param limitMs where it ends; no word is allowed to run past it
 */
fun wordsFromTokens(
    tokens: Array<String>,
    times: FloatArray,
    durations: FloatArray?,
    offsetMs: Int,
    limitMs: Int,
): List<Word> {
    data class Draft(val text: StringBuilder, val start: Int, var end: Int)

    val drafts = ArrayList<Draft>()
    var open: Draft? = null
    for (i in tokens.indices) {
        val raw = tokens[i]
        if (raw.isEmpty()) continue
        if (raw[0] == ' ' || raw[0] == '▁') open = null
        val piece = raw.trim(' ', '▁')
        if (piece.isEmpty()) continue
        val start = offsetMs + (times.getOrElse(i) { 0f } * 1000f).toInt()
        val length = durations?.getOrNull(i)?.let { (it * 1000f).toInt() } ?: 0
        val current = open ?: Draft(StringBuilder(), start, start).also {
            drafts += it
            open = it
        }
        current.text.append(piece)
        current.end = start + length
    }

    return drafts.mapIndexed { i, d ->
        val nextStart = drafts.getOrNull(i + 1)?.start ?: limitMs
        // A word lasts until the next one begins, but a pause should not be counted as part of it.
        val end = when {
            d.end > d.start -> minOf(maxOf(d.end, d.start + MIN_WORD_MS), maxOf(nextStart, d.start + 1))
            else -> minOf(nextStart, d.start + MAX_UNTIMED_WORD_MS)
        }
        Word(d.text.toString(), d.start, maxOf(end, d.start + 1))
    }
}

private const val MIN_WORD_MS = 120
private const val MAX_UNTIMED_WORD_MS = 900
