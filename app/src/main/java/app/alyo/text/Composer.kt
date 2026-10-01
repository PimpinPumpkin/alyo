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
class Composer(private val editor: Editor, private val spokenPunctuation: Boolean) {
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
        val inserted = fit(spoken.text, lowerFirst = joins)
        editor.insert(inserted)
        history += Entry(inserted, removed, spoken.literal?.let { fit(it, lowerFirst = joins) })
        if (history.size > MAX_HISTORY) history.removeAt(0)
        revertible = spoken.literal != null
        context = (context + inserted).takeLast(CONTEXT)
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
        const val MARKS = ".,;:?!"
        const val OPENERS = "([{\"'“‘/@#-"
    }
}
