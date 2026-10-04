package app.alyo

import app.alyo.tts.Phrases
import org.junit.Assert.assertEquals
import org.junit.Test

class PhrasesTest {
    @Test fun `a direction is cut at its commas`() {
        assertEquals(
            listOf("In five hundred feet,", "turn left onto Main Street,", "then take the second right."),
            Phrases.split("In five hundred feet, turn left onto Main Street, then take the second right."),
        )
    }

    @Test fun `a short start waits for the piece after it`() {
        assertEquals(listOf("Yes, I will be there at five.", "See you then."), Phrases.split("Yes, I will be there at five. See you then."))
    }

    @Test fun `a short tail joins the piece before it`() {
        assertEquals(listOf("Turn left onto Main Street, then stop."), Phrases.split("Turn left onto Main Street, then stop."))
    }

    @Test fun `a short sentence after a sentence stays its own`() {
        assertEquals(listOf("You have arrived at the house.", "Goodbye."), Phrases.split("You have arrived at the house. Goodbye."))
    }

    @Test fun `numbers and times are not cut`() {
        assertEquals(listOf("It costs 1,000 dollars at 3:45 today."), Phrases.split("It costs 1,000 dollars at 3:45 today."))
    }

    @Test fun `text without pauses is one piece`() {
        assertEquals(listOf("Turn left"), Phrases.split("  Turn left "))
        assertEquals(emptyList<String>(), Phrases.split("   "))
    }

    @Test fun `silence around speech is cut down, not away`() {
        val rate = 1000
        val samples = FloatArray(500) + FloatArray(200) { 0.5f } + FloatArray(700)
        val trimmed = Phrases.trimSilence(samples, rate, leadMs = 30, tailMs = 160)
        assertEquals(30 + 200 + 160, trimmed.size)
        assertEquals(0.5f, trimmed[30])
        assertEquals(0f, trimmed[29])
    }

    @Test fun `silence alone and speech without padding are left as they are`() {
        val quiet = FloatArray(100)
        assertEquals(100, Phrases.trimSilence(quiet, 1000).size)
        val loud = FloatArray(100) { 0.5f }
        assertEquals(100, Phrases.trimSilence(loud, 1000).size)
    }
}
