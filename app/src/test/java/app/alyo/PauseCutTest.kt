package app.alyo

import app.alyo.asr.PauseFinder
import org.junit.Assert.assertEquals
import org.junit.Test

class PauseCutTest {
    private val rate = 16_000

    /** Sound and silence by turns, as milliseconds of each, starting with sound. */
    private fun finder(vararg ms: Int): PauseFinder {
        val finder = PauseFinder()
        ms.forEachIndexed { index, length ->
            val level = if (index % 2 == 0) 0.2f else 0.001f
            finder.add(FloatArray(rate * length / 1000) { if (it % 2 == 0) level else -level })
        }
        return finder
    }

    private fun sample(ms: Int) = ms.toLong() * rate / 1000

    @Test fun `a sentence ends just before the word that follows the pause`() {
        // 2 s of speech, 0.9 s pause, speech again from 2.9 s. The recognizer notices at 3.2 s.
        val finder = finder(2000, 900, 1000)
        val cut = finder.cutInPause(sample(1900), sample(3200), sample(3400))
        assertEquals(sample(2900 - 120), cut)
    }

    @Test fun `a pause that is still going on is cut where the recognizer is`() {
        val finder = finder(2000, 1500)
        assertEquals(sample(3000), finder.cutInPause(sample(1900), sample(3000), sample(3200)))
    }

    @Test fun `a word that starts after the recognizer's position is kept whole as well`() {
        // The next word begins at 3.0 s; the recognizer has taken in audio up to 2.96 s only.
        val finder = finder(2000, 1000, 600)
        val cut = finder.cutInPause(sample(1900), sample(2960), sample(3300))
        assertEquals(sample(3000 - 120), cut)
    }

    @Test fun `without a pause the cut stays where the recognizer is`() {
        val finder = finder(3000)
        assertEquals(sample(2500), finder.cutInPause(sample(1000), sample(2500), sample(3000)))
    }

    @Test fun `sound is measured apart from silence`() {
        val finder = finder(1000, 1000, 500, 500)
        assertEquals(1500, finder.soundMs(0, sample(3000)))
        assertEquals(0, finder.soundMs(sample(1100), sample(1900)))
    }
}
