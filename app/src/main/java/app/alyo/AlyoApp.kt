package app.alyo

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.alyo.asr.AsrEngine
import app.alyo.asr.Diarizer
import app.alyo.asr.InstallState
import app.alyo.asr.ModelManager
import app.alyo.data.RecordingStore
import app.alyo.data.Settings
import app.alyo.dictation.AudioSource
import app.alyo.dictation.Dictation
import app.alyo.dictation.MicSource
import app.alyo.dictation.WavFileSource
import app.alyo.llm.Summarizer
import app.alyo.service.DownloadService
import app.alyo.service.LibraryJobs
import app.alyo.service.RecorderController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

class AlyoApp : Application() {
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
                .collect { working -> if (working) DownloadService.start(this@AlyoApp) }
        }
        models.resumeInterrupted()
        // Asking for the folder is what creates it, and adb can only put test audio in one that exists.
        if (BuildConfig.DEBUG || BuildConfig.TEST_HOOKS) getExternalFilesDir(null)
    }

    val hasMicrophone: Boolean
        get() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** True once the live model is on the phone. Without it nothing can be dictated or transcribed. */
    val hasModel: Boolean get() = models.usable(settings.pack.live) != null

    /**
     * Test builds only: if `test.wav` exists it is dictated in place of the microphone, so voice
     * typing can be exercised with nobody speaking. 16-bit mono WAV at 16 kHz. It is looked for
     * in the app's files folder, and in its external one, which adb can fill on a release build
     * where run-as is not available.
     */
    private val testAudio: File?
        get() {
            if (!BuildConfig.DEBUG && !BuildConfig.TEST_HOOKS) return null
            return listOfNotNull(filesDir, getExternalFilesDir(null)).map { File(it, "test.wav") }.firstOrNull { it.exists() }
        }

    /** A dictation set up from the current settings, not yet started. Null if the model is missing. */
    fun newDictation(listener: Dictation.Listener, singleUtterance: Boolean = false): Dictation? {
        val live = models.usable(settings.pack.live) ?: return null
        val source: () -> AudioSource = { testAudio?.let(::WavFileSource) ?: MicSource() }
        return Dictation(
            engine = engine,
            liveSpec = live,
            refinerSpec = settings.refiner(models),
            continuers = settings.pack.continuers,
            source = source,
            stopAfterSilenceMs = settings.stopAfterSeconds.value * 1000,
            singleUtterance = singleUtterance,
            keepWarmMs = settings.keepWarmMinutes.value * 60_000L,
            listener = listener,
        )
    }
}
