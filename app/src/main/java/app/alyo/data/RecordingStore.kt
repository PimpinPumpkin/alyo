package app.alyo.data

import android.content.Context
import android.net.Uri
import android.util.Log
import app.alyo.audio.M4a
import app.alyo.audio.Peaks
import app.alyo.asr.SpeakerTurn
import app.alyo.audio.Span
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Recordings on disk, one folder each under `files/recordings/<id>/`:
 *
 *  - `audio.m4a`       the recording, until it is moved to the recordings folder (see [Library])
 *  - `peaks.bin`       its waveform
 *  - `transcript.json` timed words
 *  - `meta.json`       title, date, duration
 *
 * While a recording is in progress the folder instead holds `audio.aac` (crash-safe stream) and
 * `asr.pcm` (audio spooled for the second transcription pass). Finding an `audio.aac` at launch
 * means the app died mid-recording, and [recover] turns it into a normal recording.
 */
class RecordingStore(private val context: Context) {
    private val root = File(context.filesDir, "recordings").apply { mkdirs() }

    /** Told whenever a recording's details, transcript, summary or audio change, with its id. */
    var onChange: (String) -> Unit = {}

    /** Told when a recording is deleted, with what it was, so its files outside the app can go too. */
    var onDelete: (RecordingMeta) -> Unit = {}
    private val json = Json { ignoreUnknownKeys = true }

    private val _recordings = MutableStateFlow<List<RecordingMeta>>(emptyList())
    /** Newest first. */
    val recordings: StateFlow<List<RecordingMeta>> = _recordings

    private val transcripts = HashMap<String, MutableStateFlow<Transcript>>()

    fun dir(id: String) = File(root, id)
    /**
     * The audio while it is inside the app: fresh from recording, or just edited. Once it has
     * been moved to the recordings folder this file is gone and [RecordingMeta.audioUri] says
     * where it went. Whenever this file exists, it is the truth.
     */
    fun audio(id: String) = File(dir(id), "audio.m4a")

    /** Where to play the recording from, wherever its audio is. */
    fun source(id: String): Uri = audio(id).takeIf { it.exists() }?.let(Uri::fromFile) ?: Uri.parse(meta(id)?.audioUri ?: "")

    /**
     * The audio as a plain file, for code that needs one (cutting, decoding, sharing). Audio that
     * lives in the recordings folder is copied into the cache first.
     *
     * @throws java.io.IOException if the recordings folder cannot be reached
     */
    fun localAudio(id: String): File {
        audio(id).takeIf { it.exists() }?.let { return it }
        val meta = meta(id) ?: throw java.io.FileNotFoundException("No such recording")
        val uri = Uri.parse(meta.audioUri ?: throw java.io.FileNotFoundException("The recording has no audio"))
        val cache = File(context.cacheDir, "audio").apply { mkdirs() }
        // Named for the length too, so a copy made before an edit is not mistaken for the result.
        val copy = File(cache, "$id-${meta.durationMs}.m4a")
        if (!copy.exists() || copy.length() == 0L) {
            // Room for a few; these are only ever a convenience.
            cache.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(2).forEach { it.delete() }
            val part = File(cache, "$id.part")
            (context.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException("The recordings folder is not available")).use { input ->
                part.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            part.renameTo(copy)
        }
        return copy
    }

    /** Records where a recording's files now are outside the app. */
    fun setHome(id: String, audioUri: String?, sidecarUri: String?, fileName: String?) {
        val current = meta(id) ?: return
        putMeta(current.copy(audioUri = audioUri, sidecarUri = sidecarUri, fileName = fileName), notify = false)
    }

    /** Everything about a recording but its audio, to be written next to it. */
    fun sidecar(id: String): Sidecar? {
        val meta = meta(id) ?: return null
        return Sidecar(
            meta = meta.copy(audioUri = null, sidecarUri = null, fileName = null, pendingRefine = 0),
            transcript = transcriptFlow(id).value,
            sounds = sounds(id),
            summary = summaryFlow(id).value,
            peaks = android.util.Base64.encodeToString(peaks(id).toByteArray(), android.util.Base64.NO_WRAP),
        )
    }

    /**
     * Adds a recording found in the recordings folder that this install does not know: its
     * details come from the [sidecar], its audio stays where it is.
     *
     * @return false if a recording with that id is already here
     */
    fun adopt(sidecar: Sidecar, audioUri: String, sidecarUri: String, fileName: String): Boolean {
        val id = sidecar.meta.id
        if (meta(id) != null || id.any { !(it.isLetterOrDigit() || it == '-') }) return false
        dir(id).mkdirs()
        peaksFile(id).writeBytes(runCatching { android.util.Base64.decode(sidecar.peaks, android.util.Base64.NO_WRAP) }.getOrDefault(ByteArray(0)))
        transcriptFile(id).writeText(json.encodeToString(Transcript.serializer(), sidecar.transcript))
        soundsFile(id).writeText(json.encodeToString(ListSerializer(SoundSpan.serializer()), sidecar.sounds))
        sidecar.summary?.let { summaryFile(id).writeText(json.encodeToString(Summary.serializer(), it)) }
        putMeta(sidecar.meta.copy(audioUri = audioUri, sidecarUri = sidecarUri, fileName = fileName, pendingRefine = 0), notify = false)
        return true
    }
    fun adts(id: String) = File(dir(id), "audio.aac")
    fun spool(id: String) = File(dir(id), "asr.pcm")
    fun peaksFile(id: String) = File(dir(id), "peaks.bin")
    private fun metaFile(id: String) = File(dir(id), "meta.json")
    private fun transcriptFile(id: String) = File(dir(id), "transcript.json")

    /** Reads the library from disk. Call once, off the main thread. */
    fun load() {
        val found = root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { folder ->
            val id = folder.name
            if (adts(id).exists()) recover(id) else readMeta(id)
        }
        _recordings.value = found.sortedByDescending { it.createdAt }
    }

    /** Where recordings live; for backup and restore. */
    val folder: File get() = root

    fun newId(): String = UUID.randomUUID().toString().also { dir(it).mkdirs() }

    fun meta(id: String): RecordingMeta? = _recordings.value.firstOrNull { it.id == id }

    /** Live view of a recording's transcript; updates as the second pass or the user changes it. */
    @Synchronized
    fun transcript(id: String): StateFlow<Transcript> = transcriptFlow(id)

    @Synchronized
    private fun transcriptFlow(id: String): MutableStateFlow<Transcript> = transcripts.getOrPut(id) {
        val file = transcriptFile(id)
        val loaded = runCatching { json.decodeFromString<Transcript>(file.readText()) }.getOrElse { Transcript() }
        MutableStateFlow(loaded)
    }

    fun peaks(id: String): Peaks = Peaks.load(peaksFile(id))

    private fun soundsFile(id: String) = File(dir(id), "sounds.json")

    /** Where the recording is music, applause or laughter. Small and read on demand. */
    fun sounds(id: String): List<SoundSpan> =
        runCatching { json.decodeFromString<List<SoundSpan>>(soundsFile(id).readText()) }.getOrElse { emptyList() }

    fun saveSounds(id: String, sounds: List<SoundSpan>) {
        if (dir(id).exists()) soundsFile(id).writeText(json.encodeToString(ListSerializer(SoundSpan.serializer()), sounds))
        onChange(id)
    }

    private val summaries = HashMap<String, MutableStateFlow<Summary?>>()
    private fun summaryFile(id: String) = File(dir(id), "summary.json")

    /** The recording's summary, or null until one has been written. */
    @Synchronized
    fun summary(id: String): StateFlow<Summary?> = summaryFlow(id)

    @Synchronized
    private fun summaryFlow(id: String): MutableStateFlow<Summary?> = summaries.getOrPut(id) {
        MutableStateFlow(runCatching { json.decodeFromString<Summary>(summaryFile(id).readText()) }.getOrNull())
    }

    fun saveSummary(id: String, summary: Summary) {
        if (!dir(id).exists()) return
        summaryFile(id).writeText(json.encodeToString(Summary.serializer(), summary))
        summaryFlow(id).value = summary
        onChange(id)
    }

    /** Registers a finished recording and makes it appear in the library. */
    fun add(meta: RecordingMeta, transcript: Transcript, peaks: Peaks) {
        peaks.save(peaksFile(meta.id))
        writeTranscript(meta.id, transcript)
        putMeta(meta.copy(preview = previewOf(transcript)))
    }

    /** Writes the transcript of a recording still in progress, so it survives the app being killed. */
    fun saveDraft(id: String, transcript: Transcript) {
        if (dir(id).exists()) transcriptFile(id).writeText(json.encodeToString(Transcript.serializer(), transcript))
    }

    /** @param auto the app is naming it, as opposed to the user; a user's name is never replaced. */
    fun rename(id: String, title: String, auto: Boolean = false) {
        val current = meta(id) ?: return
        if (auto && !current.autoTitled) return
        putMeta(current.copy(title = title.trim().ifEmpty { current.title }, autoTitled = auto))
    }

    fun setStarred(id: String, starred: Boolean) {
        val current = meta(id) ?: return
        putMeta(current.copy(starred = starred))
    }

    fun delete(id: String) {
        meta(id)?.let(onDelete)
        dir(id).deleteRecursively()
        synchronized(this) {
            transcripts.remove(id)
            summaries.remove(id)
        }
        _recordings.update { list -> list.filterNot { it.id == id } }
    }

    private val _labeling = MutableStateFlow<Set<String>>(emptySet())
    /** Recordings whose speakers are being told apart right now. Not saved: it is over when the app is. */
    val labeling: StateFlow<Set<String>> = _labeling

    /** Tells speakers apart with [diarize] and writes the result, showing as [labeling] meanwhile. */
    fun labelSpeakers(id: String, diarize: () -> List<SpeakerTurn>) {
        _labeling.update { it + id }
        try {
            applySpeakers(id, diarize())
        } finally {
            _labeling.update { it - id }
        }
    }

    fun setPendingRefine(id: String, pending: Int) {
        val current = meta(id) ?: return
        if (current.pendingRefine != pending) putMeta(current.copy(pendingRefine = pending))
    }

    /** Applies [change] to the transcript and saves it. */
    fun updateTranscript(id: String, change: (Transcript) -> Transcript) {
        val flow = transcriptFlow(id)
        val updated = synchronized(flow) { change(flow.value).also { flow.value = it } }
        writeTranscript(id, updated, publish = false)
        meta(id)?.let { putMeta(it.copy(preview = previewOf(updated))) }
    }

    /** Swaps in the second pass's version of one sentence, unless the user has corrected it meanwhile. */
    fun applyRefined(id: String, segment: Segment) = updateTranscript(id) { transcript ->
        transcript.copy(segments = transcript.segments.map { if (it.id == segment.id && !it.edited) segment else it })
    }

    /**
     * Labels each sentence with whoever was speaking for most of it. Speakers are renumbered in
     * order of first appearance, so "Speaker 1" is always the first voice heard.
     */
    fun applySpeakers(id: String, turns: List<SpeakerTurn>) {
        if (turns.isEmpty()) return
        updateTranscript(id) { it.withSpeakers(turns) }
    }

    /**
     * Cuts the recording down to [keep]. Audio, waveform and transcript are all rewritten to match.
     * With [asCopy] the original is left alone and a new recording is created.
     *
     * @return the id of the recording that now holds the result, or null if nothing would be left.
     */
    fun cut(id: String, keep: List<Span>, asCopy: Boolean): String? {
        val source = meta(id) ?: return null
        val snapped = keep.map { Span(M4a.snapToFrame(it.startMs), M4a.snapToFrame(it.endMs)) }.filter { it.lengthMs > 0 }
        if (snapped.isEmpty()) return null
        val targetId = if (asCopy) newId() else id
        val temp = File(dir(targetId), "audio.tmp.m4a")
        val duration = M4a.copyRanges(localAudio(id), temp, snapped)
        if (duration <= 0) {
            temp.delete()
            if (asCopy) dir(targetId).deleteRecursively()
            return null
        }
        val newSounds = sounds(id).keeping(snapped)
        val newPeaks = peaks(id).slice(snapped)
        val newTranscript = transcriptFlow(id).value.keeping(snapped)
        if (!temp.renameTo(audio(targetId))) return null
        newPeaks.save(peaksFile(targetId))
        saveSounds(targetId, newSounds)
        if (asCopy) {
            writeTranscript(targetId, newTranscript)
            putMeta(
                source.copy(
                    id = targetId,
                    title = source.title + " (edited)",
                    createdAt = System.currentTimeMillis(),
                    durationMs = duration,
                    preview = previewOf(newTranscript),
                    pendingRefine = 0,
                    // A star is a judgment about the original, not something an edit inherits.
                    starred = false,
                    // The copy's audio is the new file inside the app, not the original's.
                    audioUri = null,
                    sidecarUri = null,
                    fileName = null,
                ),
            )
        } else {
            writeTranscript(id, newTranscript)
            putMeta(source.copy(durationMs = duration, preview = previewOf(newTranscript)))
        }
        return targetId
    }

    /**
     * Writes what the recording would sound like cut down to [keep] into [target], without touching
     * the recording itself. Used to audition edits before they are saved.
     */
    fun renderPreview(id: String, keep: List<Span>, target: File): Long = M4a.copyRanges(localAudio(id), target, keep)

    /** Full transcript text of every recording whose title or words contain [query]. */
    fun search(query: String): List<RecordingMeta> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return _recordings.value
        return _recordings.value.filter { meta ->
            meta.title.lowercase().contains(needle) || transcriptFlow(meta.id).value.text.lowercase().contains(needle)
        }
    }

    private fun recover(id: String): RecordingMeta? {
        val adts = adts(id)
        val recordedAt = adts.lastModified()
        val duration = runCatching { M4a.fromAdts(adts, audio(id)) }.getOrElse {
            Log.e(TAG, "Could not recover $id", it)
            0L
        }
        adts.delete()
        spool(id).delete()
        if (duration <= 0) {
            dir(id).deleteRecursively()
            return null
        }
        // The waveform and transcript were being written alongside the audio and are still here.
        val meta = RecordingMeta(id, Titles.forDate(recordedAt) + " (recovered)", recordedAt, duration, preview = previewOf(transcriptFlow(id).value))
        metaFile(id).writeText(json.encodeToString(RecordingMeta.serializer(), meta))
        return meta
    }

    private fun readMeta(id: String): RecordingMeta? {
        val saved = runCatching { json.decodeFromString<RecordingMeta>(metaFile(id).readText()) }.getOrNull()
        // No audio here and none recorded as living elsewhere: not a recording.
        if (!audio(id).exists() && saved?.audioUri == null) {
            dir(id).deleteRecursively()
            return null
        }
        // Left behind if the app was killed before the second pass finished.
        spool(id).delete()
        // Nothing is still refining after a restart, whatever the file says.
        if (saved != null) return saved.copy(pendingRefine = 0)
        // The app died between finalizing the audio and writing its metadata: rebuild what we can.
        val modified = audio(id).lastModified()
        return RecordingMeta(id, Titles.forDate(modified), modified, M4a.durationMs(audio(id)))
    }

    private fun putMeta(meta: RecordingMeta, notify: Boolean = true) {
        metaFile(meta.id).writeText(json.encodeToString(RecordingMeta.serializer(), meta))
        _recordings.update { list -> (list.filterNot { it.id == meta.id } + meta).sortedByDescending { it.createdAt } }
        if (notify) onChange(meta.id)
    }

    private fun writeTranscript(id: String, transcript: Transcript, publish: Boolean = true) {
        transcriptFile(id).writeText(json.encodeToString(Transcript.serializer(), transcript))
        if (publish) transcriptFlow(id).value = transcript
        onChange(id)
    }

    private fun previewOf(transcript: Transcript): String =
        transcript.segments.asSequence().flatMap { it.words }.take(40).joinToString(" ") { it.text }

    private companion object {
        const val TAG = "RecordingStore"
    }
}
