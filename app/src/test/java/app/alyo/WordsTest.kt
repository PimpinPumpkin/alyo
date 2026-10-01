package app.alyo

import app.alyo.asr.wordsFromTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordsTest {
    @Test
    fun `pieces join into words and punctuation sticks to the word before it`() {
        // The shape NeMo models emit: a bare space token, then pieces, then a comma on its own.
        val tokens = arrayOf(" G", "o", "d", ",", " ", "as", " a", " dire", "c", "t")
        val times = floatArrayOf(0.32f, 0.40f, 0.48f, 0.56f, 0.72f, 0.72f, 0.88f, 1.04f, 1.12f, 1.20f)
        val words = wordsFromTokens(tokens, times, null, offsetMs = 1000, limitMs = 5000)
        assertEquals(listOf("God,", "as", "a", "direct"), words.map { it.text })
        assertEquals(listOf(1320, 1720, 1880, 2040), words.map { it.startMs })
    }

    @Test
    fun `a word ends where the next begins, and the last one does not swallow trailing silence`() {
        val words = wordsFromTokens(arrayOf(" one", " two"), floatArrayOf(0f, 0.5f), null, offsetMs = 0, limitMs = 60_000)
        assertEquals(500, words[0].endMs)
        assertTrue("last word should be capped, was ${words[1].endMs}", words[1].endMs <= 500 + 900)
    }

    @Test
    fun `reported durations are used but never overlap the following word`() {
        val words = wordsFromTokens(arrayOf(" one", " two"), floatArrayOf(0f, 0.3f), floatArrayOf(0.5f, 0.2f), offsetMs = 0, limitMs = 10_000)
        assertEquals(300, words[0].endMs)
        assertEquals(500, words[1].endMs)
    }

    @Test
    fun `sentencepiece underscore markers are treated like spaces`() {
        val words = wordsFromTokens(arrayOf("▁HEL", "LO", "▁WORLD"), floatArrayOf(0f, 0.1f, 0.4f), null, 0, 1000)
        assertEquals(listOf("HELLO", "WORLD"), words.map { it.text })
    }

    @Test
    fun `nothing in, nothing out`() {
        assertTrue(wordsFromTokens(emptyArray(), FloatArray(0), null, 0, 1000).isEmpty())
    }
}

class PauseFinderTest {
    private fun tone(ms: Int, level: Float) = FloatArray(ms * 16) { if (it % 2 == 0) level else -level }

    @Test
    fun `finds the breath in a run of speech`() {
        val finder = app.alyo.asr.PauseFinder()
        finder.add(tone(6000, 0.3f))
        finder.add(tone(300, 0.005f)) // the breath, 6.0 s to 6.3 s
        finder.add(tone(5000, 0.3f))
        val pause = finder.quietest(0, 11_300L * 16)!!
        assertTrue("found at ${pause.sample / 16} ms", pause.sample / 16 in 6050..6250)
        assertTrue("depth ${pause.depth}", pause.depth < 0.05f)
    }

    @Test
    fun `reports a shallow dip when there is no pause`() {
        val finder = app.alyo.asr.PauseFinder()
        finder.add(tone(8000, 0.3f))
        assertTrue(finder.quietest(0, 8000L * 16)!!.depth > 0.9f)
    }

    @Test
    fun `needs room for a window`() {
        val finder = app.alyo.asr.PauseFinder()
        finder.add(tone(300, 0.3f))
        assertEquals(null, finder.quietest(0, 300L * 16))
    }
}

class SummaryParseTest {
    private fun parse(reply: String) = app.alyo.llm.Summarizer.parse(reply, "test")

    @Test
    fun `reads a title and bullets`() {
        val s = parse("**Launch Date and Budget Update**\n\n- The launch moved to November.\n- A spreadsheet will follow.")
        assertEquals("Launch Date and Budget Update", s.title)
        assertEquals(listOf("The launch moved to November.", "A spreadsheet will follow."), s.points)
    }

    @Test
    fun `copes with a reply that skipped the title`() {
        val s = parse("- First point\n* Second point\n1. Third point")
        assertEquals("", s.title)
        assertEquals(listOf("First point", "Second point", "Third point"), s.points)
    }

    @Test
    fun `strips a Title label`() {
        assertEquals("Weekly sync", parse("Title: Weekly sync\n- one").title)
    }
}

class SummaryTitleTest {
    @Test
    fun `a run-on first line is not taken as a title`() {
        val s = app.alyo.llm.Summarizer.parse("Title: The history of the printing press begins long before Gutenberg, with wood block printing in China\n- one", "test")
        assertEquals("", s.title)
        assertEquals(listOf("one"), s.points)
    }
}

class SoundSpansTest {
    private val m = app.alyo.data.SoundSpan.MUSIC
    private fun spans(vararg kinds: String?, live: Boolean = false) =
        app.alyo.asr.SoundTagger.spansOf(kinds.toList(), provisional = live).map { Triple(it.kind, it.startMs, it.endMs) }

    @Test
    fun `a run of music becomes one span and a dropout inside it is absorbed`() {
        assertEquals(listOf(Triple(m, 1000, 7000)), spans(null, m, m, null, m, m, null))
    }

    @Test
    fun `a moment of something tonal is not music`() {
        assertTrue(spans(null, m, m, null, null).isEmpty())
    }

    @Test
    fun `while recording, music shows as soon as it starts`() {
        assertEquals(listOf(Triple(m, 2000, 4000)), spans(null, null, m, live = true))
    }

    @Test
    fun `brief applause counts`() {
        assertEquals(listOf(Triple("Applause", 0, 2000)), spans("Applause", null))
    }
}
