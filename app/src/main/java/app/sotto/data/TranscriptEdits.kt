package app.sotto.data

import app.sotto.audio.Span

/**
 * The transcript of a recording that has been cut down to [keep]: words in removed audio are
 * dropped and everything after a removed stretch moves earlier by its length.
 */
fun Transcript.keeping(keep: List<Span>): Transcript {
    var kept = 0L
    val shifts = keep.map { span -> (span.startMs - kept).also { kept += span.lengthMs } }
    val segments = segments.mapNotNull { segment ->
        val words = segment.words.mapNotNull { word ->
            val middle = (word.startMs + word.endMs) / 2L
            val i = keep.indexOfFirst { middle in it }
            if (i < 0) return@mapNotNull null
            val start = maxOf(word.startMs.toLong(), keep[i].startMs) - shifts[i]
            val end = minOf(word.endMs.toLong(), keep[i].endMs) - shifts[i]
            Word(word.text, start.toInt(), maxOf(end, start + 1).toInt())
        }
        if (words.isEmpty()) null else segment.copy(words = words)
    }
    return copy(segments = segments)
}

/**
 * This sentence with its text replaced by what the user typed, keeping it in sync with the audio.
 *
 * Words the user left alone keep their exact timing. Whatever they changed is spread evenly over
 * the stretch of audio the replaced words used to occupy, so tapping a corrected word still seeks
 * to the right moment. Returns null when the user deleted everything.
 */
fun Segment.withText(text: String): Segment? {
    val typed = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (typed.isEmpty()) return null
    if (typed == words.map { it.text }) return this

    // Longest common subsequence between the old words and the new ones gives the untouched anchors.
    val n = words.size
    val m = typed.size
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] = if (words[i].text == typed[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
    }

    val result = ArrayList<Word>(m)
    var i = 0
    var j = 0
    var cursor = startMs
    fun fill(untilOld: Int, untilNew: Int, nextAnchorStart: Int) {
        val count = untilNew - j
        if (count > 0) {
            // The audio the replaced words covered, or the gap between neighbors for a pure insertion.
            val from = if (untilOld > i) words[i].startMs else cursor
            val to = maxOf(if (untilOld > i) words[untilOld - 1].endMs else nextAnchorStart, from + count)
            val step = (to - from).toFloat() / count
            for (k in 0 until count) {
                val start = from + (step * k).toInt()
                result += Word(typed[j + k], start, maxOf(from + (step * (k + 1)).toInt(), start + 1))
            }
        }
        i = untilOld
        j = untilNew
    }
    while (i < n && j < m) {
        // Walk to the next anchor pair (a, b) where the old and new word are the same.
        var a = i
        var b = j
        while (a < n && b < m && words[a].text != typed[b]) {
            if (lcs[a + 1][b] >= lcs[a][b + 1]) a++ else b++
        }
        if (a >= n || b >= m) break
        fill(a, b, words[a].startMs)
        result += words[a]
        cursor = words[a].endMs
        i = a + 1
        j = b + 1
    }
    fill(n, m, endMs)
    return copy(words = result, edited = true)
}
