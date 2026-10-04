package app.alyo.tts

import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import app.alyo.asr.ModelCatalog
import app.alyo.asr.ModelManager
import app.alyo.asr.ModelSpec
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import app.alyo.asr.Synth
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.Locale

/**
 * Turns text into speech with a Piper or Supertonic voice. One model is held in memory at a
 * time, and for a little while after it was last used, since reading aloud tends to come in runs.
 */
class VoiceEngine(private val models: ModelManager, private val assets: AssetManager) {
    // Read when a language is first spoken, and kept.
    private val pronunciations = HashMap<String, Pronunciations>()

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
        val candidates = installed().filter { voice ->
            (listOf(Locale.forLanguageTag(voice.locale).language) + voice.languages).any { Locale(it).isO3Language.equals(language, ignoreCase = true) }
        }
        return candidates.firstOrNull { it.id == preferred?.id }
            ?: candidates.firstOrNull { Locale.forLanguageTag(it.locale).isO3Country.equals(country, ignoreCase = true) }
            ?: candidates.firstOrNull()
    }

    /**
     * Speaks [text], handing the audio over a phrase at a time as mono samples at the rate of
     * the voice's engine ([Synth.sampleRate]). Blocks until it is finished or stopped. [onAudio]
     * returns false to stop early.
     *
     * @param speed 1 is the voice's natural pace; 2 is twice as fast
     * @param language what the text is written in, as a two-letter code, for voices that speak several
     */
    @Synchronized
    fun speak(spec: ModelSpec, text: String, speed: Float, language: String? = null, onAudio: (FloatArray) -> Boolean) {
        handler.removeCallbacks(unload)
        stopped = false
        try {
            val tts = load(spec)
            // A named class, not a lambda: the native side looks up `invoke(float[])` returning
            // Integer by exact signature, and a compiled lambda only has the erased one.
            val sink = object : Function1<FloatArray, Int> {
                override fun invoke(samples: FloatArray): Int {
                    // A listener that has heard enough ends the phrases still to come as well.
                    // Supertonic pads each piece with about half a second of silence on both sides.
                    val audio = if (spec.synth == Synth.SUPERTONIC) Phrases.trimSilence(samples, spec.synth.sampleRate) else samples
                    if (!stopped && !onAudio(audio)) stopped = true
                    return if (stopped) 0 else 1
                }
            }
            val pace = speed.coerceIn(0.3f, 4f)
            // Made a phrase at a time, so the first words are heard while the rest is still being made.
            // The table's spellings are tuned to how the Piper voices read. Supertonic reads by
            // its own rules: it knows some of these names already and trips over the spellings.
            val spoken = Locale.forLanguageTag(spec.locale).language
            val words = if (spec.synth == Synth.PIPER) pronunciations.getOrPut(spoken) { Pronunciations.load(assets, spoken) } else Pronunciations.NONE
            for (phrase in Phrases.split(words.apply(text))) {
                if (stopped) break
                when (spec.synth) {
                    Synth.PIPER -> tts.generateWithCallback(phrase, 0, pace, sink)
                    Synth.SUPERTONIC -> tts.generateWithConfigAndCallback(
                        phrase,
                        GenerationConfig(
                            speed = pace,
                            sid = spec.speaker,
                            numSteps = SUPERTONIC_STEPS,
                            extra = mapOf("lang" to (language?.takeIf { it in spec.languages } ?: Locale.forLanguageTag(spec.locale).language)),
                        ),
                        sink,
                    )
                }
            }
        } finally {
            handler.postDelayed(unload, KEEP_MS)
        }
    }

    /** Cuts short whatever is being spoken. Safe to call from any thread. */
    fun stop() {
        stopped = true
    }

    private fun load(spec: ModelSpec): OfflineTts {
        // Voices of one model share it, so changing between them loads nothing.
        loaded?.let { (key, tts) -> if (key == spec.key) return tts else tts.release() }
        val dir = models.dir(spec)
        // Every Piper voice gets it, whatever it speaks: eSpeak reads its data once, from the
        // first voice loaded, and an English voice loaded later reads with that copy.
        if (spec.synth == Synth.PIPER) installed().filter { it.synth == Synth.PIPER }.forEach { Pronunciations.install(assets, models.dir(it)) }
        fun path(name: String) = File(dir, name).path
        val model = when (spec.synth) {
            Synth.PIPER -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = path(spec.encoder), tokens = path(spec.tokens), dataDir = path("espeak-ng-data")),
                numThreads = 2,
                provider = "cpu",
            )
            Synth.SUPERTONIC -> OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = path("duration_predictor.int8.onnx"),
                    textEncoder = path("text_encoder.int8.onnx"),
                    vectorEstimator = path("vector_estimator.int8.onnx"),
                    vocoder = path("vocoder.int8.onnx"),
                    ttsJson = path("tts.json"),
                    unicodeIndexer = path("unicode_indexer.bin"),
                    voiceStyle = path("voice.bin"),
                ),
                numThreads = 2,
                provider = "cpu",
            )
        }
        val config = OfflineTtsConfig(
            model = model,
            // Should a phrase hold several sentences all the same, they still come one at a time.
            maxNumSentences = 1,
        )
        return OfflineTts(null, config).also { loaded = spec.key to it }
    }

    companion object {
        // Fewer steps are faster and audibly worse; five is where the voices sound right.
        private const val SUPERTONIC_STEPS = 5
        private const val KEEP_MS = 120_000L
    }
}
