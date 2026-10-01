package app.alyo

import app.alyo.audio.Span
import app.alyo.data.Segment
import app.alyo.data.Transcript
import app.alyo.data.Word
import app.alyo.asr.SpeakerTurn
import app.alyo.data.withSpeakerFrom
import app.alyo.data.withSpeakers
import app.alyo.data.keeping
import app.alyo.data.withText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptEditsTest {
    // "the quick brown fox", one word per second.
    private val segment = Segment(
        id = 7,
        words = listOf(Word("the", 0, 1000), Word("quick", 1000, 2000), Word("brown", 2000, 3000), Word("fox", 3000, 4000)),
        refined = true,
    )

    @Test
    fun `unchanged text is not marked as edited`() {
        assertSame(segment, segment.withText("the  quick brown fox "))
    }

    @Test
    fun `a corrected word takes over the timing of the word it replaces`() {
        val edited = segment.withText("the quick red fox")!!
        assertEquals(Word("red", 2000, 3000), edited.words[2])
        assertEquals(segment.words[3], edited.words[3])
        assertTrue(edited.edited)
    }

    @Test
    fun `one word replaced by several shares its time evenly`() {
        val edited = segment.withText("the very quick brown fox")!!
        // "quick" is still an anchor, so the inserted word lands in the zero-length gap before it.
        assertEquals(listOf("the", "very", "quick", "brown", "fox"), edited.words.map { it.text })
        assertEquals(segment.words[1], edited.words[2])

        val split = segment.withText("the quick dark brown fox")!!
        assertEquals(segment.words[2], split.words[3])

        val replaced = segment.withText("the quick light gray fox")!!
        assertEquals(Word("light", 2000, 2500), replaced.words[2])
        assertEquals(Word("gray", 2500, 3000), replaced.words[3])
    }

    @Test
    fun `deleted words leave the others where they were`() {
        val edited = segment.withText("the fox")!!
        assertEquals(listOf(segment.words[0], segment.words[3]), edited.words)
    }

    @Test
    fun `every word keeps a positive length and order is preserved`() {
        val edited = segment.withText("a b c d e f g h i j k l")!!
        assertEquals(12, edited.words.size)
        assertTrue(edited.words.all { it.endMs > it.startMs })
        assertEquals(edited.words.map { it.startMs }.sorted(), edited.words.map { it.startMs })
    }

    @Test
    fun `clearing the text removes the sentence`() {
        assertNull(segment.withText("   "))
    }

    @Test
    fun `cropping drops words outside the kept audio and shifts the rest`() {
        val transcript = Transcript(segments = listOf(segment))
        val cropped = transcript.keeping(listOf(Span(1000, 3000)))
        assertEquals(listOf(Word("quick", 0, 1000), Word("brown", 1000, 2000)), cropped.segments.single().words)
    }

    @Test
    fun `cutting out the middle joins what is left`() {
        val transcript = Transcript(segments = listOf(segment))
        val cut = transcript.keeping(listOf(Span(0, 1000), Span(3000, 4000)))
        assertEquals(listOf(Word("the", 0, 1000), Word("fox", 1000, 2000)), cut.segments.single().words)
    }

    @Test
    fun `a sentence that loses all its words disappears`() {
        val transcript = Transcript(segments = listOf(segment, Segment(8, listOf(Word("later", 9000, 9500)))))
        val cut = transcript.keeping(listOf(Span(0, 4000)))
        assertEquals(listOf(7L), cut.segments.map { it.id })
    }
}

class TopicTest {
    private fun transcript(text: String) = Transcript(
        segments = listOf(Segment(0, text.split(' ').mapIndexed { i, w -> Word(w, i * 300, i * 300 + 250) })),
    )

    @Test
    fun `names a recording after what keeps coming up`() {
        val topic = app.alyo.data.Titles.topic(
            transcript("Okay so the budget is tight this quarter. We spent most of the budget on hardware and the hardware arrived late. I think the budget needs another look."),
        )
        assertEquals("Budget, Hardware", topic)
    }

    @Test
    fun `prefers names`() {
        val topic = app.alyo.data.Titles.topic(transcript("I talked to Priya about the launch. Then Priya said the launch could slip."))
        assertEquals("Priya, Launch", topic)
    }

    @Test
    fun `says nothing when nothing stands out`() {
        assertNull(app.alyo.data.Titles.topic(transcript("Hello there, this is just a quick test.")))
    }

    @Test
    fun aSpeakerChangeMidSentenceSplitsTheSegmentThere() {
        val words = listOf(Word("So", 0, 200), Word("yes.", 200, 500), Word("Right,", 600, 900), Word("exactly.", 900, 1400))
        val transcript = Transcript(segments = listOf(Segment(0, words, speaker = 0), Segment(1, listOf(Word("And", 2000, 2300)), speaker = 0), Segment(2, listOf(Word("later", 5000, 5300)), speaker = 0)))
        val changed = transcript.withSpeakerFrom(listOf(0L, 1L), words[2], speaker = 1)
        assertEquals(listOf("So yes.", "Right, exactly.", "And", "later"), changed.segments.map { it.text })
        assertEquals(listOf(0, 1, 1, 0), changed.segments.map { it.speaker })
        assertEquals(4, changed.segments.map { it.id }.distinct().size)
    }

    @Test
    fun aSentenceTwoPeopleShareIsSplitWhereTheVoiceChanges() {
        val words = listOf(Word("Ready?", 0, 400), Word("Yes,", 900, 1200), Word("ready.", 1200, 1700), Word("Good.", 2300, 2700))
        val transcript = Transcript(segments = listOf(Segment(7, words)))
        val turns = listOf(SpeakerTurn(3, 0, 500), SpeakerTurn(1, 850, 1750), SpeakerTurn(3, 2250, 2800))
        val labeled = transcript.withSpeakers(turns)
        assertEquals(listOf("Ready?", "Yes, ready.", "Good."), labeled.segments.map { it.text })
        assertEquals(listOf(0, 1, 0), labeled.segments.map { it.speaker })
        assertEquals(7L, labeled.segments.first().id)
        assertEquals(3, labeled.segments.map { it.id }.distinct().size)
    }
}
