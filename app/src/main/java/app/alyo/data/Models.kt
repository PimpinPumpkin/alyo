package app.alyo.data

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
    /** Which voice this is, numbered from 0 in order of first appearance; null when speakers were not told apart. */
    val speaker: Int? = null,
) {
    val startMs: Int get() = words.firstOrNull()?.startMs ?: 0
    val endMs: Int get() = words.lastOrNull()?.endMs ?: 0
    val text: String get() = words.joinToString(" ") { it.text }
}

@Serializable
data class Transcript(
    val language: String = "en",
    val segments: List<Segment> = emptyList(),
    /** Names the user has given speakers, by number. Unnamed ones show as "Speaker 1", "Speaker 2". */
    val speakerNames: Map<Int, String> = emptyMap(),
) {
    fun speakerName(speaker: Int): String = speakerNames[speaker] ?: "Speaker ${speaker + 1}"
    val hasSpeakers: Boolean get() = segments.mapNotNull { it.speaker }.distinct().size > 1
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
    val starred: Boolean = false,
    /** True while the title is one the app chose (the date, or a topic), so it may still be improved on. */
    val autoTitled: Boolean = false,
    /**
     * Where the audio is once it has been moved out to the recordings folder, as a content URI.
     * Null while it is still inside the app (`audio.m4a` in the recording's own folder).
     */
    val audioUri: String? = null,
    /** The file next to the audio that holds the transcript and details, so the folder stands on its own. */
    val sidecarUri: String? = null,
    /** The name the two files were given, without extension. They are renamed when the title changes. */
    val fileName: String? = null,
)

/**
 * Everything about a recording except its audio, as one file kept next to the audio in the
 * recordings folder. With it, a folder of recordings can be read back into a fresh install.
 */
@Serializable
data class Sidecar(
    val version: Int = 1,
    val meta: RecordingMeta,
    val transcript: Transcript = Transcript(),
    val sounds: List<SoundSpan> = emptyList(),
    val summary: Summary? = null,
    /** The waveform, base64. */
    val peaks: String = "",
)

/** What a recording was about, written by the on-device language model. */
@Serializable
data class Summary(
    val title: String,
    val points: List<String>,
    /** Which model wrote it, shown so a rough summary can be blamed on the right thing. */
    val model: String = "",
) {
    val text: String get() = (listOf(title) + points.map { "- $it" }).filter { it.isNotBlank() }.joinToString("\n")
}

/** A stretch of a recording that is something other than talk: music, applause, laughter. */
@Serializable
data class SoundSpan(
    val kind: String,
    @SerialName("s") val startMs: Int,
    @SerialName("e") val endMs: Int,
) {
    companion object {
        const val MUSIC = "Music"
        const val APPLAUSE = "Applause"
        const val LAUGHTER = "Laughter"
    }
}
