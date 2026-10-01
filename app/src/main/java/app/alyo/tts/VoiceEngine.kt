package app.alyo.tts

import android.os.Handler
import android.os.Looper
import app.alyo.asr.ModelCatalog
import app.alyo.asr.ModelManager
import app.alyo.asr.ModelSpec
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.Locale

/**
 * Turns text into speech with a Piper voice. One voice is held in memory at a time, and for a
 * little while after it was last used, since reading aloud tends to come in runs.
 */
class VoiceEngine(private val models: ModelManager) {
    private var loaded: Pair<String, OfflineTts>? = null
    private val handler = Handler(Looper.getMainLooper())
    private val unload = Runnable { synchronized(this) { loaded?.second?.release(); loaded = null } }

    @Volatile private var stopped = false

    /** The voices on this phone. */
    fun installed(): List<ModelSpec> = ModelCatalog.voices.filter(models::isInstalled)

    /**
     * The installed voice to use for a language: [preferred] if it speaks it, otherwise one from
     * the same country, otherwise any that speaks the language. Languages and countries are the
     * three-letter codes Android's speech framework uses ("eng", "USA").
     */
    fun voiceFor(language: String?, country: String?, preferred: ModelSpec?): ModelSpec? {
        val candidates = installed().filter { Locale.forLanguageTag(it.locale).isO3Language.equals(language, ignoreCase = true) }
        return candidates.firstOrNull { it.id == preferred?.id }
            ?: candidates.firstOrNull { Locale.forLanguageTag(it.locale).isO3Country.equals(country, ignoreCase = true) }
            ?: candidates.firstOrNull()
    }

    /**
     * Speaks [text], handing the audio over a sentence at a time as 22.05 kHz mono samples.
     * Blocks until it is finished or stopped. [onAudio] returns false to stop early.
     *
     * @param speed 1 is the voice's natural pace; 2 is twice as fast
     */
    @Synchronized
    fun speak(spec: ModelSpec, text: String, speed: Float, onAudio: (FloatArray) -> Boolean) {
        handler.removeCallbacks(unload)
        stopped = false
        try {
            val tts = load(spec)
            // A named class, not a lambda: the native side looks up `invoke(float[])` returning
            // Integer by exact signature, and a compiled lambda only has the erased one.
            val sink = object : Function1<FloatArray, Int> {
                override fun invoke(samples: FloatArray): Int = if (stopped || !onAudio(samples)) 0 else 1
            }
            tts.generateWithCallback(text, 0, speed.coerceIn(0.3f, 4f), sink)
        } finally {
            handler.postDelayed(unload, KEEP_MS)
        }
    }

    /** Cuts short whatever is being spoken. Safe to call from any thread. */
    fun stop() {
        stopped = true
    }

    private fun load(spec: ModelSpec): OfflineTts {
        loaded?.let { (id, tts) -> if (id == spec.id) return tts else tts.release() }
        val dir = models.dir(spec)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(dir, spec.encoder).path,
                    tokens = File(dir, spec.tokens).path,
                    dataDir = File(dir, "espeak-ng-data").path,
                ),
                numThreads = 2,
                provider = "cpu",
            ),
            // One sentence at a time, so the first words are heard while the rest is still being made.
            maxNumSentences = 1,
        )
        return OfflineTts(null, config).also { loaded = spec.id to it }
    }

    companion object {
        const val SAMPLE_RATE = 22050
        private const val KEEP_MS = 120_000L
    }
}
