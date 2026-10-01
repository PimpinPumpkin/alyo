package app.alyo.text

import app.alyo.data.Word

/**
 * Turns punctuation that was said out loud ("comma", "period", "new line") into the marks
 * themselves.
 *
 * The hard part is telling a command from the same word used as a word: "I don't want to hear
 * about that period" should keep its noun. A spoken mark is taken as a command unless the words
 * around it make it read as a noun phrase: a determiner straight before it with no pause ("that
 * period", "a comma"), or a word after it that it is commonly the head of ("period of time").
 * A pause before the word tips it back to a command, since that is how people dictate marks.
 * It will sometimes guess wrong either way, so the caller keeps [Result.literal] to put the words
 * back with one tap.
 */
object SpokenPunctuation {
    /**
     * @param text the sentence with spoken marks applied. It may begin with a mark or a line
     *   break, when the command was the first thing said: that belongs on the end of whatever
     *   came before.
     * @param literal the sentence as plain words, or null if nothing was converted
     */
    data class Result(val text: String, val literal: String?)

    private class Command(val phrase: List<String>, val mark: String)

    private val commands = listOf(
        Command(listOf("new", "paragraph"), "\n\n"),
        Command(listOf("new", "line"), "\n"),
        Command(listOf("newline"), "\n"),
        Command(listOf("question", "mark"), "?"),
        Command(listOf("exclamation", "point"), "!"),
        Command(listOf("exclamation", "mark"), "!"),
        Command(listOf("full", "stop"), "."),
        Command(listOf("semi", "colon"), ";"),
        Command(listOf("semicolon"), ";"),
        Command(listOf("period"), "."),
        Command(listOf("comma"), ","),
        Command(listOf("colon"), ":"),
    )

    /** Words that make the one after them a noun: "that period", "a comma", "the trial period". */
    private val before = setOf(
        "a", "an", "the", "this", "that", "these", "those", "my", "your", "his", "her", "its", "our", "their",
        "each", "every", "any", "some", "no", "one", "another", "same", "whole", "entire", "first", "second",
        "third", "last", "next", "time", "trial", "grace", "waiting", "class", "long", "short", "brief", "of",
        "per", "which", "what", "oxford", "serial", "missing", "extra",
    )

    /** Words that make the one before them a noun: "period of time", "comma separated". */
    private val after = setOf(
        "of", "key", "keys", "button", "symbol", "character", "sign", "separated", "delimited", "splice",
        "cancer", "pieces", "piece", "drama", "cramps",
    )

    private const val MARKS = ".,;:?!"

    /** A gap this long before a spoken mark reads as a deliberate command, whatever came before it. */
    private const val PAUSE_MS = 350

    private fun core(word: String): String = word.lowercase().trim { !it.isLetterOrDigit() }

    fun apply(words: List<Word>): Result {
        val literal = words.joinToString(" ") { it.text }
        val out = StringBuilder()
        var converted = false
        var capitalizeNext = false
        // Whether anything other than commands has been written: a mark needs a word to sit on.
        var wroteWord = false
        var i = 0

        fun append(text: String) {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append(' ')
            out.append(if (capitalizeNext) text.replaceFirstChar { it.uppercase() } else text)
            capitalizeNext = false
            wroteWord = true
        }

        while (i < words.size) {
            val command = commands.firstOrNull { c ->
                i + c.phrase.size <= words.size && c.phrase.indices.all { k ->
                    val word = words[i + k].text
                    // "question, mark" is two things; "question mark?" is one.
                    core(word) == c.phrase[k] && (k == c.phrase.lastIndex || word.last().isLetterOrDigit())
                }
            }
            if (command == null || !isCommand(words, i, command, wroteWord)) {
                append(words[i].text)
                i++
                continue
            }
            converted = true
            val lineBreak = command.mark.startsWith("\n")
            // The model punctuates too; a spoken mark replaces whatever it put there. A line
            // break keeps it ("Dear Sam," then a new line).
            if (!lineBreak) while (out.isNotEmpty() && out.last() in MARKS) out.setLength(out.length - 1)
            out.append(command.mark)
            capitalizeNext = lineBreak || command.mark in ".?!"
            i += command.phrase.size
        }
        return if (converted) Result(out.toString(), literal) else Result(literal, null)
    }

    private fun isCommand(words: List<Word>, at: Int, command: Command, wroteWord: Boolean): Boolean {
        val next = words.getOrNull(at + command.phrase.size)
        val lineBreak = command.mark.startsWith("\n")
        if (next != null && core(next.text) in after) return false
        val previous = words.getOrNull(at - 1)
        if (previous == null || !wroteWord || previous.text.last() in ".?!") {
            // Nothing before it in this sentence. Said on its own it is a command for the text
            // already there; a line break can open a sentence; "Period pieces are fun" cannot.
            return next == null || lineBreak || commands.any { c -> core(next.text) == c.phrase[0] }
        }
        val gap = words[at].startMs - previous.endMs
        if (core(previous.text) in before && gap < PAUSE_MS) return false
        return true
    }
}
