package app.sotto.data

import android.content.Context
import android.util.Log
import app.sotto.audio.M4a
import app.sotto.audio.Peaks
import app.sotto.audio.Span
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Recordings on disk, one folder each under `files/recordings/<id>/`:
 *
 *  - `audio.m4a`       the recording
 *  - `peaks.bin`       its waveform
 *  - `transcript.json` timed words
 *  - `meta.json`       title, date, duration
 *
 * While a recording is in progress the folder instead holds `audio.aac` (crash-safe stream) and
 * `asr.pcm` (audio spooled for the second transcription pass). Finding an `audio.aac` at launch
 * means the app died mid-recording, and [recover] turns it into a normal recording.
 */
class RecordingStore(context: Context) {
    private val root = File(context.filesDir, "recordings").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    private val _recordings = MutableStateFlow<List<RecordingMeta>>(emptyList())
    /** Newest first. */
    val recordings: StateFlow<List<RecordingMeta>> = _recordings

    private val transcripts = HashMap<String, MutableStateFlow<Transcript>>()

    fun dir(id: String) = File(root, id)
    fun audio(id: String) = File(dir(id), "audio.m4a")
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

    fun rename(id: String, title: String) {
        val current = meta(id) ?: return
        putMeta(current.copy(title = title.trim().ifEmpty { current.title }))
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
        synchronized(this) { transcripts.remove(id) }
        _recordings.update { list -> list.filterNot { it.id == id } }
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
        val duration = M4a.copyRanges(audio(id), temp, snapped)
        if (duration <= 0) {
            temp.delete()
            if (asCopy) dir(targetId).deleteRecursively()
            return null
        }
        val newPeaks = peaks(id).slice(snapped)
        val newTranscript = transcriptFlow(id).value.keeping(snapped)
        if (!temp.renameTo(audio(targetId))) return null
        newPeaks.save(peaksFile(targetId))
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
                ),
            )
        } else {
            writeTranscript(id, newTranscript)
            putMeta(source.copy(durationMs = duration, preview = previewOf(newTranscript)))
        }
        return targetId
    }

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
        if (!audio(id).exists()) {
            dir(id).deleteRecursively()
            return null
        }
        // Left behind if the app was killed before the second pass finished.
        spool(id).delete()
        val saved = runCatching { json.decodeFromString<RecordingMeta>(metaFile(id).readText()) }.getOrNull()
        // Nothing is still refining after a restart, whatever the file says.
        if (saved != null) return saved.copy(pendingRefine = 0)
        // The app died between finalising the audio and writing its metadata: rebuild what we can.
        val modified = audio(id).lastModified()
        return RecordingMeta(id, Titles.forDate(modified), modified, M4a.durationMs(audio(id)))
    }

    private fun putMeta(meta: RecordingMeta) {
        metaFile(meta.id).writeText(json.encodeToString(RecordingMeta.serializer(), meta))
        _recordings.update { list -> (list.filterNot { it.id == meta.id } + meta).sortedByDescending { it.createdAt } }
    }

    private fun writeTranscript(id: String, transcript: Transcript, publish: Boolean = true) {
        transcriptFile(id).writeText(json.encodeToString(Transcript.serializer(), transcript))
        if (publish) transcriptFlow(id).value = transcript
    }

    private fun previewOf(transcript: Transcript): String =
        transcript.segments.asSequence().flatMap { it.words }.take(40).joinToString(" ") { it.text }

    private companion object {
        const val TAG = "RecordingStore"
    }
}
