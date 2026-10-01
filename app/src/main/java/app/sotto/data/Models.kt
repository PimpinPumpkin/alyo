package app.sotto.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One transcribed word, positioned on the recording's own timeline (milliseconds from the start of the audio file). */
@Serializable
data class Word(
    @SerialName("t") val text: String,
    @SerialName("s") val startMs: Int,
    @SerialName("e") val endMs: Int,
)

/**
 * A run of speech between two pauses; shown as one paragraph.
 *
 * [refined] is false while the words are still the live first-pass guess and true once the slower,
 * more accurate model has rewritten them. [edited] marks a paragraph the user has corrected by hand,
 * which the refiner must never overwrite.
 */
@Serializable
data class Segment(
    val id: Long,
    val words: List<Word>,
    val refined: Boolean = false,
    val edited: Boolean = false,
) {
    val startMs: Int get() = words.firstOrNull()?.startMs ?: 0
    val endMs: Int get() = words.lastOrNull()?.endMs ?: 0
    val text: String get() = words.joinToString(" ") { it.text }
}

@Serializable
data class Transcript(
    val language: String = "en",
    val segments: List<Segment> = emptyList(),
) {
    val text: String get() = segments.joinToString("\n\n") { it.text }
    val isEmpty: Boolean get() = segments.all { it.words.isEmpty() }
}

@Serializable
data class RecordingMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    /** First words of the transcript, for the list row; the full text is searched from transcript.json. */
    val preview: String = "",
    /** Segments still waiting for the accurate second pass. Non-zero means the transcript will keep improving. */
    val pendingRefine: Int = 0,
)
