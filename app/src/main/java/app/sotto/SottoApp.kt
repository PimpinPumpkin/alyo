package app.sotto

import android.app.Application
import app.sotto.asr.AsrEngine
import app.sotto.asr.InstallState
import app.sotto.asr.ModelManager
import app.sotto.data.RecordingStore
import app.sotto.data.Settings
import app.sotto.llm.Summarizer
import app.sotto.service.DownloadService
import app.sotto.service.RecorderController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SottoApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: Settings
        private set
    lateinit var store: RecordingStore
        private set
    lateinit var models: ModelManager
        private set
    lateinit var engine: AsrEngine
        private set
    lateinit var recorder: RecorderController
        private set
    lateinit var summarizer: Summarizer
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        store = RecordingStore(this)
        models = ModelManager(this, scope)
        engine = AsrEngine(models)
        recorder = RecorderController(this, store, models, engine, settings, scope)
        summarizer = Summarizer(store, models, settings, isRecording = { recorder.busy.value }, scope = scope)
        scope.launch(Dispatchers.IO) { store.load() }
        // A download gets a foreground service for as long as it runs. Without one, Android cuts
        // a backgrounded app's network within a minute or so of the screen going off.
        scope.launch {
            models.states.map { states -> states.values.any { it is InstallState.Downloading } }.distinctUntilChanged().collect { downloading ->
                if (downloading) DownloadService.start(this@SottoApp)
            }
        }
        models.resumeInterrupted()
    }
}
