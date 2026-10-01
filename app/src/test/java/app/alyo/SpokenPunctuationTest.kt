package app.alyo

import app.alyo.data.Word
import app.alyo.text.SpokenPunctuation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenPunctuationTest {
    /** Words 300 ms long with 50 ms between them; a `|` before a word puts a half second pause there. */
    private fun words(text: String): List<Word> {
        var at = 0
        return text.split(' ').map { raw ->
            if (raw.startsWith("|")) at += 500
            Word(raw.removePrefix("|"), at, at + 300).also { at += 350 }
        }
    }

    private fun apply(text: String) = SpokenPunctuation.apply(words(text))

    @Test
    fun plainSentenceIsLeftAlone() {
        val result = apply("Hello, how are you?")
        assertEquals("Hello, how are you?", result.text)
        assertNull(result.literal)
    }

    @Test
    fun spokenMarksReplaceTheWords() {
        assertEquals("Hello, how are you?", apply("Hello comma how are you question mark").text)
        assertEquals("Stop! Now.", apply("Stop exclamation point now period").text)
    }

    @Test
    fun spokenMarkReplacesTheModelsOwn() {
        // The model heard a sentence end and wrote its own full stop after the command word.
        assertEquals("Are you there?", apply("Are you there question mark.").text)
        assertEquals("Hello, how are you.", apply("Hello, comma, how are you.").text)
    }

    @Test
    fun nounAfterADeterminerStays() {
        assertNull(apply("I don't want to hear about that period.").literal)
        assertNull(apply("You are missing a comma there.").literal)
        assertNull(apply("It was a long period of time.").literal)
        assertNull(apply("Press the question mark key.").literal)
    }

    @Test
    fun pauseBeforeTheWordMakesItACommand() {
        assertEquals("I don't want to hear about that.", apply("I don't want to hear about that |period").text)
    }

    @Test
    fun literalIsKeptForPuttingTheWordsBack() {
        assertEquals("We met in the Jurassic period", apply("We met in the Jurassic period").literal)
    }

    @Test
    fun lineBreaks() {
        assertEquals("Dear Sam,\nThanks for the note.", apply("Dear Sam, new line thanks for the note.").text)
        assertEquals("One.\n\nTwo.", apply("One. New paragraph. Two.").text)
    }

    @Test
    fun commandOnItsOwnOpensWithTheMark() {
        assertEquals(".", apply("Period.").text)
        assertEquals("\n", apply("New line.").text)
    }

    @Test
    fun wordAtTheStartOfASentenceStays() {
        assertNull(apply("Period dramas are my favorite.").literal)
        assertNull(apply("Period costumes look great.").literal)
    }
}
