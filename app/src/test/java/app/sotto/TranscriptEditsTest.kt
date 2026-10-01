package app.sotto

import app.sotto.audio.Span
import app.sotto.data.Segment
import app.sotto.data.Transcript
import app.sotto.data.Word
import app.sotto.data.keeping
import app.sotto.data.withText
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

        val replaced = segment.withText("the quick light grey fox")!!
        assertEquals(Word("light", 2000, 2500), replaced.words[2])
        assertEquals(Word("grey", 2500, 3000), replaced.words[3])
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
