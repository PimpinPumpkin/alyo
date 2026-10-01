package app.sotto.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.sotto.asr.AsrEngine
import app.sotto.asr.LiveTranscriber
import app.sotto.asr.ModelManager
import app.sotto.audio.MicSource
import app.sotto.audio.PcmSource
import app.sotto.audio.RecordingSession
import app.sotto.audio.WavFileSource
import app.sotto.data.RecordingMeta
import app.sotto.data.RecordingStore
import app.sotto.data.Settings
import app.sotto.data.Titles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * The single place recording is started and stopped from, whether by the UI or the notification.
 * It outlives any screen; [RecordingService] exists only to keep the process alive while
 * [busy] is true.
 */
class RecorderController(
    private val context: Context,
    private val store: RecordingStore,
    private val models: ModelManager,
    private val engine: AsrEngine,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val _session = MutableStateFlow<RecordingSession?>(null)
    val session: StateFlow<RecordingSession?> = _session

    private val settling = MutableStateFlow(0)
    private val _busy = MutableStateFlow(false)
    /** True while recording, and afterwards until the last sentence has had its second pass. */
    val busy: StateFlow<Boolean> = _busy

    /** @param wav debug only: record from this file instead of the microphone. */
    @Synchronized
    fun start(wav: File? = null): RecordingSession {
        _session.value?.let { return it }
        val id = store.newId()
        val now = System.currentTimeMillis()
        val pack = settings.pack
        val refiner = settings.chosenQuality.refiner.takeIf { models.isInstalled(it) }
            ?: pack.qualities.map { it.refiner }.firstOrNull { models.isInstalled(it) }
        val openSource: () -> PcmSource = if (wav != null) ({ WavFileSource(wav) }) else ({ MicSource() })
        val session = RecordingSession(
            id = id,
            startedAt = now,
            title = Titles.forDate(now),
            store = store,
            scope = scope,
            openSource = openSource,
            autoTitle = { settings.autoTitle.value },
            transcriberFactory = if (!models.isInstalled(pack.live)) null else { onRefined ->
                LiveTranscriber(engine, pack.live, refiner, pack.continuers, store.spool(id), scope, onRefined)
            },
        )
        _session.value = session
        refreshBusy()
        ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        session.begin()
        return session
    }

    fun setPaused(paused: Boolean) {
        _session.value?.setPaused(paused)
    }

    /**
     * Ends the current recording and saves it. Returns null if it was too short to keep.
     *
     * The work runs on the app's own scope: the screen that asked for it is usually torn down the
     * instant the session ends, and its coroutines with it, but the save must still complete.
     */
    suspend fun stop(): RecordingMeta? {
        val session = takeSession() ?: return null
        settling.update { it + 1 }
        refreshBusy()
        return scope.async {
            session.save(onSettled = {
                settling.update { it - 1 }
                refreshBusy()
            })
        }.await()
    }

    suspend fun discard() {
        val session = takeSession() ?: return
        settling.update { it + 1 }
        refreshBusy()
        scope.async {
            try {
                session.discard()
            } finally {
                settling.update { it - 1 }
                refreshBusy()
            }
        }.await()
    }

    @Synchronized
    private fun takeSession(): RecordingSession? = _session.value.also { _session.value = null }

    // Same lock as start(): the models may only be freed when no session exists or is being created.
    @Synchronized
    private fun refreshBusy() {
        val busy = _session.value != null || settling.value > 0
        _busy.value = busy
        if (!busy) engine.releaseAll()
    }
}
