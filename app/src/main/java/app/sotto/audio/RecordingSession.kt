package app.sotto.audio

import android.util.Log
import app.sotto.asr.LiveTranscriber
import app.sotto.asr.SoundTagger
import app.sotto.data.RecordingMeta
import app.sotto.data.RecordingStore
import app.sotto.data.Segment
import app.sotto.data.SoundSpan
import app.sotto.data.Titles
import app.sotto.data.Transcript
import app.sotto.data.Word
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.sotto.asr.SpeakerTurn
import java.io.File
import java.io.FileOutputStream

/**
 * One recording in progress: a thread pulling samples from the microphone and fanning them out to
 * the AAC encoder, the waveform and the transcriber.
 */
class RecordingSession(
    val id: String,
    val startedAt: Long,
    title: String,
    private val store: RecordingStore,
    private val scope: CoroutineScope,
    private val openSource: () -> PcmSource,
    private val autoTitle: () -> Boolean,
    private val sounds: SoundTagger?,
    /** True when something else has taken the microphone (a phone call). Recording pauses itself until it is false again. */
    private val interrupted: () -> Boolean = { false },
    /** Tells speakers apart once the transcript is final; given the spooled audio, returns who spoke when. */
    private val diarize: ((File) -> List<SpeakerTurn>)? = null,
    transcriberFactory: ((onRefined: (Segment) -> Unit) -> LiveTranscriber)?,
) {
    val peaks = Peaks()

    private val _peakCount = MutableStateFlow(0)
    /** Number of waveform bars so far; changes are the cue to redraw [peaks]. */
    val peakCount: StateFlow<Int> = _peakCount

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    val title = MutableStateFlow(title)

    private val _failure = MutableStateFlow<String?>(null)
    /** Set if capture could not start or died, e.g. another app holds the microphone. */
    val failure: StateFlow<String?> = _failure

    private val _sourceEnded = MutableStateFlow(false)
    /** True once a finite source (the debug WAV) has run out. A microphone never does. */
    val sourceEnded: StateFlow<Boolean> = _sourceEnded

    private val saveLock = Any()
    private var saved = false
    private val transcriber: LiveTranscriber? = transcriberFactory?.invoke { segment ->
        // Before the recording is saved, refinements are picked up from the transcriber's own
        // list; afterwards they have to be written through to the stored transcript.
        synchronized(saveLock) { if (saved) store.applyRefined(id, segment) }
    }

    /** Music and the like heard so far. */
    val soundSpans: StateFlow<List<SoundSpan>> = sounds?.spans ?: MutableStateFlow(emptyList())

    val hasTranscript: Boolean get() = transcriber != null
    val segments: StateFlow<List<Segment>> = transcriber?.segments ?: MutableStateFlow(emptyList())
    val partial: StateFlow<List<Word>> = transcriber?.partial ?: MutableStateFlow(emptyList())

    // Likewise the transcript: saved each time a sentence is closed or rewritten, until the
    // recording is filed properly.
    private val drafts: Job? = transcriber?.let { t ->
        scope.launch(Dispatchers.IO) {
            t.segments.collect { segments ->
                synchronized(saveLock) { if (!saved) store.saveDraft(id, Transcript(segments = segments)) }
            }
        }
    }

    @Volatile private var stopRequested = false
    private val captureDone = CompletableDeferred<Unit>()
    private val thread = Thread(::capture, "sotto-capture")

    fun begin() = thread.start()

    fun setPaused(paused: Boolean) {
        // The user's own choice overrides an automatic pause: it will not resume by itself after this.
        autoPaused = false
        _interruption.value = false
        _paused.value = paused
    }

    @Volatile private var autoPaused = false
    private val _interruption = MutableStateFlow(false)
    /** True while recording is paused because a call or another app has the microphone. */
    val interruption: StateFlow<Boolean> = _interruption

    @Volatile private var activeSource: PcmSource? = null
    /** Name of the microphone in use ("Phone", "Pixel Buds"), or null if unknown. */
    val inputName: String? get() = (activeSource as? MicSource)?.inputName

    /** Switches microphone mid-recording. Null lets Android choose. Does nothing for a source that is not a microphone. */
    fun selectInput(device: android.media.AudioDeviceInfo?) {
        (activeSource as? MicSource)?.select(device)
    }

    val usesMicrophone: Boolean get() = activeSource is MicSource

    private fun capture() {
        var source: PcmSource? = null
        var encoder: AacWriter? = null
        var peaksOut: FileOutputStream? = null
        try {
            source = openSource()
            activeSource = source
            encoder = AacWriter(store.adts(id))
            // The waveform is written as it grows for the same reason the audio is: so a recording
            // cut short by a crash comes back whole.
            peaksOut = FileOutputStream(store.peaksFile(id))
            var peaksWritten = 0
            val decimator = Decimator()
            val buffer = ShortArray(BLOCK_SAMPLES)
            var samples = 0L
            var sinceFlush = 0
            var capturing = false
            var checkedAt = 0L
            while (!stopRequested) {
                // Twice a second: has a call taken the microphone, or given it back?
                val now = System.currentTimeMillis()
                if (now - checkedAt > 500) {
                    checkedAt = now
                    val taken = interrupted()
                    if (taken && !_paused.value) {
                        autoPaused = true
                        _paused.value = true
                    } else if (!taken && autoPaused && _paused.value) {
                        autoPaused = false
                        _paused.value = false
                    }
                    _interruption.value = autoPaused
                }
                // A file can be read far faster than it can be heard. Wait for the transcriber
                // rather than queue the whole file in memory.
                if (transcriber != null && transcriber.backlogSeconds > MAX_BACKLOG_SECONDS) {
                    Thread.sleep(20)
                    continue
                }
                if (_paused.value) {
                    if (capturing) {
                        source.stop()
                        capturing = false
                    }
                    Thread.sleep(30)
                    continue
                }
                if (!capturing) {
                    source.start()
                    capturing = true
                }
                val n = source.read(buffer)
                if (n < 0) {
                    _sourceEnded.value = true
                    break
                }
                if (n == 0) continue
                encoder.write(buffer, n)
                if (peaks.add(buffer, n) > 0) _peakCount.value = peaks.size
                val speech = decimator.process(buffer, n)
                transcriber?.accept(speech)
                sounds?.accept(speech)
                samples += n
                _elapsedMs.value = AudioSpec.samplesToMs(samples)
                sinceFlush += n
                if (sinceFlush >= AudioSpec.SAMPLE_RATE) {
                    encoder.flush()
                    peaksOut.write(peaks.toByteArray(peaksWritten))
                    peaksOut.flush()
                    peaksWritten = peaks.size
                    sinceFlush = 0
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Capture failed", e)
            _failure.value = e.message ?: "Recording failed"
        } finally {
            runCatching { source?.stop() }
            runCatching { source?.release() }
            runCatching { encoder?.close() }
            runCatching { peaksOut?.close() }
            transcriber?.finish()
            sounds?.finish()
            captureDone.complete(Unit)
        }
    }

    /**
     * Stops capture and files the recording in the library. The transcript is saved as soon as the
     * live pass has caught up; the accurate second pass keeps going in the background and updates
     * the stored transcript sentence by sentence. [onSettled] runs when that is finished too.
     *
     * @return the saved recording, or null if there was nothing worth keeping.
     */
    suspend fun save(onSettled: () -> Unit): RecordingMeta? {
        stopRequested = true
        captureDone.await()
        val duration = withContext(Dispatchers.IO) {
            runCatching { M4a.fromAdts(store.adts(id), store.audio(id)) }.getOrDefault(0L).also { store.adts(id).delete() }
        }
        if (duration < MIN_DURATION_MS) {
            discardFiles()
            onSettled()
            return null
        }
        transcriber?.awaitLive()
        sounds?.await()
        drafts?.cancel()
        val meta = withContext(Dispatchers.IO) {
            synchronized(saveLock) {
                val meta = RecordingMeta(
                    id, title.value, startedAt, duration,
                    pendingRefine = transcriber?.pending?.value ?: 0,
                    autoTitled = title.value == Titles.forDate(startedAt),
                )
                store.add(meta, Transcript(segments = segments.value), peaks)
                sounds?.let { store.saveSounds(id, it.spans.value) }
                saved = true
                meta
            }
        }
        scope.launch(Dispatchers.IO) {
            transcriber?.let { t ->
                val watcher = launch { t.pending.collect { store.setPendingRefine(id, it) } }
                t.awaitRefined()
                watcher.cancel()
                t.release()
            }
            // Who said what, from the same spooled audio, before it is thrown away.
            diarize?.let { run -> runCatching { store.applySpeakers(id, run(store.spool(id))) }.onFailure { Log.e(TAG, "Speaker labels failed", it) } }
            store.setPendingRefine(id, 0)
            store.spool(id).delete()
            // Name it after its subject, unless the user has already given it a name of their own.
            if (autoTitle()) Titles.topic(store.transcript(id).value)?.let { store.rename(id, it, auto = true) }
            onSettled()
        }
        return meta
    }

    /** Stops capture and throws the recording away. */
    suspend fun discard() {
        stopRequested = true
        transcriber?.cancel()
        captureDone.await()
        discardFiles()
    }

    private suspend fun discardFiles() {
        drafts?.cancelAndJoin()
        transcriber?.let {
            it.cancel()
            it.awaitRefined()
            it.release()
        }
        withContext(Dispatchers.IO) { store.delete(id) }
    }

    private companion object {
        const val TAG = "RecordingSession"
        const val BLOCK_SAMPLES = 1920 // 40 ms
        const val MIN_DURATION_MS = 300L
        const val MAX_BACKLOG_SECONDS = 20f
    }
}
