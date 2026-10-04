package app.alyo.text

import app.alyo.dictation.Dictation

/** The text field being dictated into, reduced to what [Composer] needs from it. */
interface Editor {
    /** Up to [n] characters before the cursor. */
    fun textBefore(n: Int): String

    /** Shows [text] as the still-changing, underlined run at the cursor, replacing the previous one. Empty removes it. */
    fun setComposing(text: String)

    /** Inserts [text] at the cursor as ordinary text. */
    fun insert(text: String)
    fun deleteBefore(n: Int)

    /** Runs [edits] as one change, so the field does not flicker through the steps. */
    fun batch(edits: () -> Unit)
}

/**
 * Decides what goes into the text field as a dictation proceeds.
 *
 * The field always reads as: what was there, then the sentences that are final, then the tail
 * (underlined, still changing). This class fits each piece to its surroundings (a space before
 * it or not, a capital or not), applies spoken punctuation, and remembers what it inserted so the
 * last sentence can be taken back.
 */
class Composer(
    private val editor: Editor,
    private val spokenPunctuation: Boolean,
    /** Keep the period the speech model puts at every pause. Off: a pause alone does not end the sentence. */
    private val pausePeriods: Boolean = true,
) {
    /**
     * @param inserted exactly what went into the field
     * @param removed a mark deleted from the end of the earlier text to make way for a spoken one
     * @param literal what [inserted] would have been with spoken punctuation left as words
     */
    private class Entry(val inserted: String, val removed: String, val literal: String?)

    private val history = ArrayList<Entry>()

    /** The underlined run as it stands in the field. */
    private var shown = ""

    /** The text before the insertion point, as far back as matters. */
    private var context = ""

    /** True right after a sentence with a converted mark went in: backspace puts the words back. */
    private var revertible = false

    /**
     * A period nobody said, left out when the last sentence went in: where that sentence ended on the
     * dictation's timeline, and the end of the text as it was then. Going on soon after carries the
     * sentence on; going on after a long pause puts the period in after all.
     */
    private var owedPeriodAtMs = -1
    private var owedPeriodAfter = ""

    /** Set when the field restarted input: it has turned the underlined run into ordinary text. */
    private var detached = false

    val canUndo: Boolean get() = history.isNotEmpty() || shown.isNotEmpty()

    /** Call when dictation moves to a different field. */
    fun reset() {
        history.clear()
        shown = ""
        context = ""
        revertible = false
        detached = false
        owedPeriodAtMs = -1
    }

    /** The field restarted input on its own (many do after every change). The underline is gone but its text is still there. */
    fun inputRestarted() {
        if (shown.isNotEmpty()) detached = true
    }

    /** Applies the next piece of news from the dictation. */
    fun update(final: Dictation.Sentence?, tail: String) {
        editor.batch {
            reattach()
            // With nothing of ours on screen the cursor may have been moved, so look again.
            if (shown.isEmpty()) context = editor.textBefore(CONTEXT)
            if (final != null) {
                if (shown.isNotEmpty()) editor.setComposing("")
                shown = ""
                commit(final)
            }
            val rendered = renderTail(tail)
            if (rendered != shown) {
                editor.setComposing(rendered)
                shown = rendered
            }
        }
    }

    private fun reattach() {
        if (!detached) return
        detached = false
        // Take the orphaned text back out so it can be replaced like any other tail.
        if (editor.textBefore(shown.length) == shown) editor.deleteBefore(shown.length)
        shown = ""
    }

    private fun commit(sentence: Dictation.Sentence) {
        if (sentence.words.isEmpty()) return
        val spoken = if (spokenPunctuation) SpokenPunctuation.apply(sentence.words) else SpokenPunctuation.Result(sentence.words.joinToString(" ") { it.text }, null)
        var removed = ""
        // A sentence that was only split because it ran long carries on from the one before:
        // "...dynasty. Where..." becomes "...dynasty, where...".
        val joins = sentence.continues && context.endsWith(".")
        if (joins) {
            editor.deleteBefore(1)
            editor.insert(",")
            context = context.dropLast(1) + ","
        }
        val startsWithMark = spoken.text.first() in MARKS
        if (startsWithMark && context.isNotEmpty() && context.last() in MARKS) {
            removed = context.last().toString()
            editor.deleteBefore(1)
            context = context.dropLast(1)
        }
        // The period held back at the last pause goes in now if the speaker took a long break,
        // and only if the text still ends where that sentence left it.
        var lead = ""
        val owedAt = owedPeriodAtMs
        owedPeriodAtMs = -1
        if (owedAt >= 0 && !startsWithMark && context.endsWith(owedPeriodAfter)) {
            // a sentence only split because it ran long carries on after a comma, as it does with the period kept
            if (sentence.continues) lead = ","
            else if (sentence.words.first().startMs - owedAt >= LONG_PAUSE_MS) lead = "."
            context += lead
        }
        var text = spoken.text
        var literal = spoken.literal
        if (!pausePeriods && !spoken.spokenEnd && text.length > 1 && text.endsWith(".") && !text.endsWith("..")) {
            text = text.dropLast(1)
            if (literal != null && literal.endsWith(".")) literal = literal.dropLast(1)
            owedPeriodAtMs = sentence.words.last().endMs
        }
        val inserted = lead + fit(text, lowerFirst = joins)
        editor.insert(inserted)
        if (owedPeriodAtMs >= 0) owedPeriodAfter = (context.dropLast(lead.length) + inserted).takeLast(OWED_CONTEXT)
        history += Entry(inserted, removed, literal?.let { lead + fit(it, lowerFirst = joins) })
        if (history.size > MAX_HISTORY) history.removeAt(0)
        revertible = spoken.literal != null
        context = (context.dropLast(lead.length) + inserted).takeLast(CONTEXT)
    }

    /** [text] as it should read after [context]: spaced off from it, and capitalized only where a sentence starts. */
    private fun fit(text: String, lowerFirst: Boolean = false): String {
        val attaches = text.first() in MARKS || text.first() == '\n'
        val space = if (!attaches && needsSpace()) " " else ""
        val body = when {
            attaches -> text
            lowerFirst || !atSentenceStart() -> lowerFirstWord(text)
            else -> text.replaceFirstChar { it.uppercase() }
        }
        return space + body
    }

    private fun renderTail(tail: String): String {
        if (tail.isEmpty()) return ""
        val text = if (atSentenceStart()) tail.replaceFirstChar { it.uppercase() } else tail
        return (if (needsSpace()) " " else "") + text
    }

    private fun needsSpace(): Boolean {
        val last = context.lastOrNull() ?: return false
        return !last.isWhitespace() && last !in OPENERS
    }

    private fun atSentenceStart(): Boolean {
        val last = context.trimEnd(' ', '\t').lastOrNull() ?: return true
        return last in ".?!\n" || last in OPENERS && context.length == 1
    }

    /** Lowers the capital the speech model put on a sentence that turns out to continue one. "I" keeps its own. */
    private fun lowerFirstWord(text: String): String {
        val word = text.takeWhile { !it.isWhitespace() }
        val core = word.trim { !it.isLetterOrDigit() }
        if (core == "I" || core.startsWith("I'") || core.startsWith("I’")) return text
        // An acronym or a name with a capital inside it is not a sentence capital.
        if (core.drop(1).any { it.isUpperCase() }) return text
        return text.replaceFirstChar { it.lowercase() }
    }

    /**
     * Takes back the most recent thing said: the tail if there is one, otherwise the last
     * sentence that went in.
     *
     * @return true if the tail was what went, which the dictation then has to forget too
     */
    fun undo(): Boolean {
        revertible = false
        owedPeriodAtMs = -1
        if (shown.isNotEmpty() || detached) {
            editor.batch {
                reattach()
                if (shown.isNotEmpty()) editor.setComposing("")
            }
            shown = ""
            return true
        }
        val entry = history.removeLastOrNull() ?: return false
        if (editor.textBefore(entry.inserted.length) != entry.inserted) {
            // The text has been edited since; what is remembered no longer describes it.
            history.clear()
            return false
        }
        editor.batch {
            editor.deleteBefore(entry.inserted.length)
            if (entry.removed.isNotEmpty()) editor.insert(entry.removed)
        }
        return false
    }

    /**
     * Backspace. Straight after a spoken mark was converted it puts the words back ("." becomes
     * " period" again); otherwise it deletes one character.
     *
     * @return true if the tail was dropped, which the dictation then has to forget too
     */
    fun backspace(): Boolean {
        if (shown.isNotEmpty() || detached) return undo()
        val entry = history.lastOrNull()
        if (revertible && entry?.literal != null && editor.textBefore(entry.inserted.length) == entry.inserted) {
            revertible = false
            editor.batch {
                editor.deleteBefore(entry.inserted.length)
                if (entry.removed.isNotEmpty()) editor.insert(entry.removed)
                context = editor.textBefore(CONTEXT)
                val literal = fit(entry.literal.trimStart())
                editor.insert(literal)
                history[history.lastIndex] = Entry(literal, "", null)
            }
            return false
        }
        revertible = false
        editor.deleteBefore(1)
        // Keep the remembered sentence in step, so undo still removes what is left of it.
        if (entry != null) {
            history.removeAt(history.lastIndex)
            if (entry.inserted.length > 1) history += Entry(entry.inserted.dropLast(1), entry.removed, null)
        }
        return false
    }

    private companion object {
        const val CONTEXT = 48
        const val MAX_HISTORY = 50
        const val OWED_CONTEXT = 12

        /** Silence between two sentences, on top of the pause that closed the first, after which the second starts anew. */
        const val LONG_PAUSE_MS = 3000
        const val MARKS = ".,;:?!"
        const val OPENERS = "([{\"'“‘/@#-"
    }
}
