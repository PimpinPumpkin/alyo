package app.alyo

import app.alyo.asr.Word
import app.alyo.dictation.Dictation
import app.alyo.text.Composer
import app.alyo.text.Editor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerTest {
    /** A text field with the cursor at the end, and at most one underlined run just before it. */
    private class Field(initial: String = "") : Editor {
        var committed = initial
        var underlined = ""
        val text: String get() = committed + underlined

        override fun textBefore(n: Int): String = text.takeLast(n)
        override fun setComposing(text: String) {
            underlined = text
        }

        override fun insert(text: String) {
            // As a real field does: committed text takes the place of the underlined run.
            committed += text
            underlined = ""
        }

        override fun deleteBefore(n: Int) {
            committed = (committed + underlined).dropLast(n)
            underlined = ""
        }

        override fun batch(edits: () -> Unit) = edits()

        /** What a field does when it restarts input: the underline goes, the text stays. */
        fun restart() {
            committed += underlined
            underlined = ""
        }
    }

    private fun sentence(text: String, continues: Boolean = false): Dictation.Sentence {
        var at = 0
        return Dictation.Sentence(text.split(' ').map { Word(it, at, at + 300).also { at += 350 } }, continues)
    }

    @Test
    fun tailIsShownThenReplacedByTheFinalSentence() {
        val field = Field()
        val composer = Composer(field, spokenPunctuation = true)
        composer.update(null, "hello there")
        assertEquals("Hello there", field.underlined)
        composer.update(sentence("Hello there."), "")
        assertEquals("Hello there.", field.committed)
        assertEquals("", field.underlined)
    }

    @Test
    fun nextSentenceIsSpacedOffAndKeepsItsCapital() {
        val field = Field()
        val composer = Composer(field, true)
        composer.update(sentence("Hello there."), "how are")
        assertEquals("Hello there. How are", field.text)
        composer.update(sentence("How are you?"), "")
        assertEquals("Hello there. How are you?", field.text)
    }

    @Test
    fun dictatingIntoTheMiddleOfASentenceDropsTheCapital() {
        val field = Field("Tell Sam")
        val composer = Composer(field, true)
        composer.update(sentence("That I will be late."), "")
        assertEquals("Tell Sam that I will be late.", field.text)
        val second = Field("So,")
        Composer(second, true).update(sentence("I think so."), "")
        assertEquals("So, I think so.", second.text)
    }

    @Test
    fun secondSentenceWaitsUnderlinedWhileTheFirstIsRewritten() {
        val field = Field()
        val composer = Composer(field, true)
        composer.update(null, "first one second one")
        composer.update(sentence("First one."), "second one")
        assertEquals("First one.", field.committed)
        assertEquals(" Second one", field.underlined)
    }

    @Test
    fun undoTakesTheTailThenTheLastSentence() {
        val field = Field("Hi.")
        val composer = Composer(field, true)
        composer.update(sentence("One."), "")
        composer.update(sentence("Two."), "three")
        assertTrue(composer.undo())
        assertEquals("Hi. One. Two.", field.text)
        assertFalse(composer.undo())
        assertEquals("Hi. One.", field.text)
        composer.undo()
        assertEquals("Hi.", field.text)
        assertFalse(composer.canUndo)
    }

    @Test
    fun undoLeavesEditedTextAlone() {
        val field = Field()
        val composer = Composer(field, true)
        composer.update(sentence("One."), "")
        field.committed = "One. And more"
        composer.undo()
        assertEquals("One. And more", field.text)
    }

    @Test
    fun backspacePutsASpokenMarkBackAsAWord() {
        val field = Field()
        val composer = Composer(field, true)
        composer.update(sentence("We met in the Jurassic period"), "")
        assertEquals("We met in the Jurassic.", field.text)
        composer.backspace()
        assertEquals("We met in the Jurassic period", field.text)
        composer.backspace()
        assertEquals("We met in the Jurassic perio", field.text)
    }

    @Test
    fun spokenMarkOnItsOwnAttachesToTheTextBefore() {
        val field = Field("Is that so.")
        val composer = Composer(field, true)
        composer.update(sentence("Question mark."), "")
        assertEquals("Is that so?", field.text)
        composer.undo()
        assertEquals("Is that so.", field.text)
    }

    @Test
    fun splitSentenceIsJoinedBackWithAComma() {
        val field = Field()
        val composer = Composer(field, true)
        composer.update(sentence("It began in the Ming dynasty."), "")
        composer.update(sentence("Where trade was growing.", continues = true), "")
        assertEquals("It began in the Ming dynasty, where trade was growing.", field.text)
    }

    @Test
    fun restartedInputDoesNotDoubleTheTail() {
        val field = Field()
        val composer = Composer(field, true)
        composer.update(null, "hello there")
        field.restart()
        composer.inputRestarted()
        composer.update(null, "hello there friend")
        assertEquals("Hello there friend", field.text)
        composer.update(sentence("Hello there, friend."), "")
        assertEquals("Hello there, friend.", field.text)
    }

    @Test
    fun spokenPunctuationCanBeTurnedOff() {
        val field = Field()
        Composer(field, spokenPunctuation = false).update(sentence("Hello comma world."), "")
        assertEquals("Hello comma world.", field.text)
    }
}
