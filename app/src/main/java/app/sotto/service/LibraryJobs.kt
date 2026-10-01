package app.sotto.service

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import app.sotto.asr.AsrEngine
import app.sotto.asr.Diarizer
import app.sotto.asr.LiveTranscriber
import app.sotto.asr.ModelManager
import app.sotto.asr.SoundTagger
import app.sotto.audio.AudioDecoder
import app.sotto.audio.Decimator
import app.sotto.audio.RecordingSession
import app.sotto.data.RecordingStore
import app.sotto.data.Settings
import app.sotto.data.Titles
import app.sotto.data.Transcript
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Work on the library that is not recording: bringing in an audio file from elsewhere, and
 * transcribing a recording again. Both push existing audio through the same pipeline the
 * microphone feeds, as fast as the phone can manage, one job at a time.
 */
class LibraryJobs(
    private val context: Context,
    private val store: RecordingStore,
    private val models: ModelManager,
    private val engine: AsrEngine,
    private val settings: Settings,
    private val diarizer: Diarizer,
    private val scope: CoroutineScope,
) {
    /**
     * @param recordingId the recording being re-transcribed, or null for an import that is not in the library yet
     * @param progress 0 to 1 through the audio, or null once it is all read and only polishing is left
     */
    data class Job(val key: String, val title: String, val recordingId: String?, val progress: Float?)

    private val _active = MutableStateFlow<List<Job>>(emptyList())
    val active: StateFlow<List<Job>> = _active

    private val _errors = MutableStateFlow<String?>(null)
    /** The most recent failure, for showing once. */
    val errors: StateFlow<String?> = _errors
    fun clearError() { _errors.value = null }

    // The speech models are too big to run two jobs at once.
    private val oneAtATime = Mutex()

    fun canTranscribe(): Boolean = models.usable(settings.pack.live) != null

    /** Copies an audio file into the library as a new recording, transcribing it on the way in. */
    fun import(uri: Uri) {
        val name = displayName(uri)
        val key = "import-" + System.nanoTime()
        _active.update { it + Job(key, name, null, 0f) }
        scope.launch(Dispatchers.IO) {
            try {
                oneAtATime.withLock { runImport(key, uri, name) }
            } catch (e: Throwable) {
                Log.e(TAG, "Import failed", e)
                _errors.value = "Could not import $name: ${e.message ?: "unsupported file"}"
            } finally {
                _active.update { list -> list.filterNot { it.key == key } }
            }
        }
    }

    /** Throws away a recording's transcript and makes a new one from its audio. */
    fun retranscribe(id: String) {
        val meta = store.meta(id) ?: return
        if (_active.value.any { it.recordingId == id }) return
        val key = "again-$id"
        _active.update { it + Job(key, meta.title, id, 0f) }
        scope.launch(Dispatchers.IO) {
            try {
                oneAtATime.withLock { runRetranscribe(key, id) }
            } catch (e: Throwable) {
                Log.e(TAG, "Transcription failed", e)
                _errors.value = "Could not transcribe ${meta.title}: ${e.message ?: "unknown error"}"
            } finally {
                store.setPendingRefine(id, 0)
                _active.update { list -> list.filterNot { it.key == key } }
            }
        }
    }

    private fun setProgress(key: String, progress: Float?) = _active.update { list -> list.map { if (it.key == key) it.copy(progress = progress) else it } }

    private suspend fun runImport(key: String, uri: Uri, name: String) {
        val decoder = AudioDecoder(context, uri)
        val id = store.newId()
        val now = System.currentTimeMillis()
        val pack = settings.pack
        val refiner = installedRefiner()
        engine.retain()
        val settled = CompletableDeferred<Unit>()
        try {
            val session = RecordingSession(
                id = id,
                startedAt = now,
                title = name.substringBeforeLast('.').ifBlank { Titles.forDate(now) },
                store = store,
                scope = scope,
                openSource = { decoder },
                autoTitle = { false },
                sounds = SoundTagger(context.assets, scope),
                diarize = diarizer.takeIf { settings.speakerLabels.value && it.isReady() }?.let { it::turns },
                transcriberFactory = models.usable(pack.live)?.let { live ->
                    { onRefined -> LiveTranscriber(engine, live, refiner, pack.continuers, store.spool(id), scope, onRefined) }
                },
                language = pack.code,
            )
            session.begin()
            while (!session.sourceEnded.value && session.failure.value == null) {
                setProgress(key, decoder.progress)
                delay(250)
            }
            session.failure.value?.let { error(it) }
            setProgress(key, null)
            if (session.save(onSettled = { settled.complete(Unit) }) == null) error("the file has no usable audio")
            // The recording is in the library now and shows its own "polishing" state; the job is
            // only kept open so the models stay loaded until the second pass is through.
            _active.update { list -> list.filterNot { it.key == key } }
            settled.await()
        } finally {
            engine.release()
        }
    }

    private suspend fun runRetranscribe(key: String, id: String) {
        val pack = settings.pack
        val live = models.usable(pack.live) ?: error("no speech model is installed")
        val decoder = AudioDecoder(store.audio(id))
        val spool = store.spool(id)
        engine.retain()
        store.setPendingRefine(id, 1)
        val transcriber = LiveTranscriber(engine, live, installedRefiner(), pack.continuers, spool, scope)
        val sounds = SoundTagger(context.assets, scope)
        try {
            val decimator = Decimator()
            val buffer = ShortArray(1920)
            while (true) {
                if (transcriber.backlogSeconds > 20f) {
                    delay(20)
                    continue
                }
                val n = decoder.read(buffer)
                if (n < 0) break
                val speech = decimator.process(buffer, n)
                transcriber.accept(speech)
                sounds.accept(speech)
                setProgress(key, decoder.progress)
            }
            transcriber.finish()
            sounds.finish()
            setProgress(key, null)
            transcriber.awaitLive()
            transcriber.awaitRefined()
            sounds.await()
            // Names the user gave speakers survive; the words they were attached to do not.
            store.updateTranscript(id) { Transcript(language = pack.code, segments = transcriber.segments.value, speakerNames = it.speakerNames) }
            store.saveSounds(id, sounds.spans.value)
            if (settings.speakerLabels.value && diarizer.isReady()) store.applySpeakers(id, diarizer.turns(spool))
        } finally {
            transcriber.cancel()
            sounds.finish()
            transcriber.release()
            decoder.release()
            spool.delete()
            engine.release()
        }
    }

    private fun installedRefiner() = settings.refiner(models)

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: "Imported audio"

    private companion object {
        const val TAG = "LibraryJobs"
    }
}
