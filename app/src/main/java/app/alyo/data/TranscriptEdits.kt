package app.alyo.data

import app.alyo.asr.SpeakerTurn
import app.alyo.audio.Span

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

/** Sound markers of a recording cut down to [keep], following the same rules as the transcript. */
fun List<SoundSpan>.keeping(keep: List<Span>): List<SoundSpan> {
    var kept = 0L
    val out = ArrayList<SoundSpan>()
    for (span in keep) {
        val shift = span.startMs - kept
        for (sound in this) {
            val start = maxOf(sound.startMs.toLong(), span.startMs)
            val end = minOf(sound.endMs.toLong(), span.endMs)
            if (end - start >= 1000) out += SoundSpan(sound.kind, (start - shift).toInt(), (end - shift).toInt())
        }
        kept += span.lengthMs
    }
    return out
}

/**
 * Hands the rest of a paragraph to another speaker, starting at [word]. The paragraph is the run
 * of segments [paragraph]; the segment holding [word] is cut in two there, since a segment has one
 * speaker. A transcript with no speakers yet gets one first, so the change has something to
 * differ from.
 */
fun Transcript.withSpeakerFrom(paragraph: List<Long>, word: Word, speaker: Int): Transcript {
    var nextId = (segments.maxOfOrNull { it.id } ?: 0L) + 1
    var passed = false
    val changed = segments.flatMap { segment ->
        val base = if (segment.speaker == null) segment.copy(speaker = 0) else segment
        when {
            segment.id !in paragraph -> listOf(base)
            passed -> listOf(base.copy(speaker = speaker))
            else -> {
                val at = segment.words.indexOf(word)
                when {
                    at < 0 -> listOf(base)
                    at == 0 -> listOf(base.copy(speaker = speaker)).also { passed = true }
                    else -> listOf(base.copy(words = segment.words.take(at)), base.copy(id = nextId++, words = segment.words.drop(at), speaker = speaker)).also { passed = true }
                }
            }
        }
    }
    return copy(segments = changed)
}

/**
 * Labels the transcript with who spoke when. Segments are cut where the speaker pauses, not where
 * the speaker changes, so one segment can hold a question and its answer. Each sentence in it is
 * given to whoever did most of the talking while it was said, and the segment is split wherever
 * that changes. Sentences, not words: where two voices overlap the turns flicker between them
 * several times a second, and following that would shred the text.
 *
 * Speakers are renumbered from 0 in order of first appearance.
 */
fun Transcript.withSpeakers(turns: List<SpeakerTurn>): Transcript {
    if (turns.isEmpty()) return this
    fun speakerOf(words: List<Word>): Int? {
        val heard = HashMap<Int, Int>()
        for (word in words) {
            for (turn in turns) {
                val shared = minOf(word.endMs, turn.endMs) - maxOf(word.startMs, turn.startMs)
                if (shared > 0) heard[turn.speaker] = (heard[turn.speaker] ?: 0) + shared
            }
        }
        return heard.maxByOrNull { it.value }?.key
    }
    val order = LinkedHashMap<Int, Int>()
    var nextId = (segments.maxOfOrNull { it.id } ?: 0L) + 1
    val labeled = segments.flatMap { segment ->
        // The segment as sentences: [first word, last word, speaker or -1 when nobody was heard].
        val runs = ArrayList<IntArray>()
        var start = 0
        segment.words.forEachIndexed { i, word ->
            if (word.text.lastOrNull() in SENTENCE_END || i == segment.words.lastIndex) {
                runs += intArrayOf(start, i, speakerOf(segment.words.subList(start, i + 1)) ?: -1)
                start = i + 1
            }
        }
        // A sentence nobody was heard saying goes with its neighbor, and neighbors by the same
        // speaker are one run.
        val known = runs.firstOrNull { it[2] >= 0 }?.get(2) ?: return@flatMap listOf(segment.copy(speaker = null))
        var previous = known
        for (run in runs) {
            if (run[2] < 0) run[2] = previous
            previous = run[2]
        }
        val merged = ArrayList<IntArray>()
        for (run in runs) {
            if (merged.isNotEmpty() && merged.last()[2] == run[2]) merged.last()[1] = run[1] else merged += run
        }
        merged.mapIndexed { index, run ->
            segment.copy(
                id = if (index == 0) segment.id else nextId++,
                words = segment.words.subList(run[0], run[1] + 1),
                speaker = order.getOrPut(run[2]) { order.size },
            )
        }
    }
    return copy(segments = labeled)
}

private val SENTENCE_END = setOf('.', '?', '!', '\u2026', '\u3002')
