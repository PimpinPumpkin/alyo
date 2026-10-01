package app.sotto

import app.sotto.asr.wordsFromTokens
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
