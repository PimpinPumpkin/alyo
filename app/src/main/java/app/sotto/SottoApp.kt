package app.sotto

import android.app.Application
import app.sotto.asr.AsrEngine
import app.sotto.asr.ModelManager
import app.sotto.data.RecordingStore
import app.sotto.data.Settings
import app.sotto.service.RecorderController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        store = RecordingStore(this)
        models = ModelManager(this, scope)
        engine = AsrEngine(models)
        recorder = RecorderController(this, store, models, engine, settings, scope)
        scope.launch(Dispatchers.IO) { store.load() }
        models.resumeInterrupted()
    }
}
