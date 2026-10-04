package app.alyo

import app.alyo.tts.Pronunciations
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PronunciationsTest {
    private val table = Pronunciations.parse("# a note\n\nTucson\ttoosonn\nSault Ste. Marie\tsoo saint marie\nGila\theela\nCôte-des-Neiges\tcoat day nezh\nbroken line\n")

    @Test fun `a word in the table is respelled whatever its capitals`() {
        assertEquals("Turn left toward toosonn.", table.apply("Turn left toward Tucson."))
        assertEquals("toosonn's airport", table.apply("TUCSON's airport"))
    }

    @Test fun `a word inside a longer word is left alone`() {
        assertEquals("Gilas and Tucsonans", table.apply("Gilas and Tucsonans"))
    }

    @Test fun `names of several words and with accents are found`() {
        assertEquals("Exit to soo saint marie, then heela Bend", table.apply("Exit to Sault Ste. Marie, then Gila Bend"))
        assertEquals("shuh coat day nezh", table.apply("shuh Côte-des-Neiges"))
    }

    @Test fun `text without any of the words comes back as it was`() {
        assertEquals("Turn right.", table.apply("Turn right."))
        assertEquals("Turn right.", Pronunciations.NONE.apply("Turn right."))
    }

    @Test fun `the table the app ships can be read and has no line that is ignored`() {
        val file = File("src/main/assets/pronunciation/en.tsv")
        val lines = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(lines.isNotEmpty())
        for (line in lines) assertEquals("needs a word, a tab and a spelling: $line", 2, line.split('\t').filter { it.isNotBlank() }.size)
        assertEquals("toosonn", Pronunciations.parse(file.readText()).apply("Tucson"))
    }
}
