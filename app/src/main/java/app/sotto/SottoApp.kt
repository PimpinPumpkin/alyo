package app.sotto

import android.app.Application
import app.sotto.asr.AsrEngine
import app.sotto.asr.Diarizer
import app.sotto.asr.InstallState
import app.sotto.asr.ModelManager
import app.sotto.data.RecordingStore
import app.sotto.data.Settings
import app.sotto.llm.Summarizer
import app.sotto.service.DownloadService
import app.sotto.service.LibraryJobs
import app.sotto.service.RecorderController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
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
    lateinit var jobs: LibraryJobs
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        store = RecordingStore(this)
        models = ModelManager(this, scope)
        engine = AsrEngine(models)
        val diarizer = Diarizer(this, models)
        recorder = RecorderController(this, store, models, engine, settings, diarizer, scope)
        jobs = LibraryJobs(this, store, models, engine, settings, diarizer, scope)
        summarizer = Summarizer(store, models, settings, isRecording = { recorder.busy.value }, scope = scope)
        scope.launch(Dispatchers.IO) { store.load() }
        // A download gets a foreground service for as long as it runs. Without one, Android cuts
        // a backgrounded app's network within a minute or so of the screen going off.
        scope.launch {
            // The same goes for importing or re-transcribing, which can run for minutes.
            combine(models.states, jobs.active) { states, active -> states.values.any { it is InstallState.Downloading } || active.isNotEmpty() }
                .distinctUntilChanged()
                .collect { working -> if (working) DownloadService.start(this@SottoApp) }
        }
        models.resumeInterrupted()
    }
}
