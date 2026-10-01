package app.alyo.asr

import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Owns the loaded speech models. Loading takes a second or two and hundreds of megabytes, so each
 * model is loaded once and shared, and kept for a while after a dictation ends: someone who
 * dictates one message is likely to dictate the next, and should not wait for it again. The
 * recorder and the keyboard share them.
 */
class AsrEngine(private val models: ModelManager) {
    // Keyed by model and pause length: a recorder and a keyboard want different pauses.
    private var live: Pair<String, OnlineRecognizer>? = null
    private var refiner: Pair<String, OfflineRecognizer>? = null

    private val handler = Handler(Looper.getMainLooper())
    private val unload = Runnable { unloadNow() }

    /** True when the live model is in memory, so a dictation can begin without a loading pause. */
    val warm: Boolean get() = synchronized(liveLock) { live != null }

    // Each model has its own lock, so the two load side by side at the start of a dictation.
    private val liveLock = Any()
    private val refinerLock = Any()

    /**
     * @param sentencePause seconds of silence after speech that end a sentence and hand it to the
     * second pass. A recording can afford to wait and split less; when dictating, the wait for
     * punctuation is felt, so it is shorter there.
     */
    fun live(spec: ModelSpec, sentencePause: Float = RECORDING_PAUSE): OnlineRecognizer = synchronized(liveLock) {
        val key = "${spec.id}@$sentencePause"
        live?.let { (id, recognizer) -> if (id == key) return@synchronized recognizer else recognizer.release() }
        val dir = models.dir(spec)
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, spec.encoder).path,
                    decoder = File(dir, spec.decoder).path,
                    joiner = File(dir, spec.joiner).path,
                ),
                tokens = File(dir, spec.tokens).path,
                numThreads = 2,
                provider = "cpu",
            ),
            endpointConfig = EndpointConfig(
                // Silence with nothing said yet: just move the segment start forward.
                rule1 = EndpointRule(false, 2.4f, 0f),
                // A pause after speech ends the sentence and hands it to the second pass.
                rule2 = EndpointRule(true, sentencePause, 0f),
                // Long unbroken speech is split at breaths by the caller, which can pick the
                // moment; this blunt cut is only a backstop so the hypothesis cannot grow forever.
                rule3 = EndpointRule(false, 0f, 120f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        OnlineRecognizer(null, config).also { live = key to it }
    }

    fun refiner(spec: ModelSpec): OfflineRecognizer = synchronized(refinerLock) {
        refiner?.let { (id, recognizer) -> if (id == spec.id) return@synchronized recognizer else recognizer.release() }
        val dir = models.dir(spec)
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, spec.encoder).path,
                    decoder = File(dir, spec.decoder).path,
                    joiner = File(dir, spec.joiner).path,
                ),
                tokens = File(dir, spec.tokens).path,
                modelType = "nemo_transducer",
                numThreads = 2,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        OfflineRecognizer(null, config).also { refiner = spec.id to it }
    }

    private var users = 0

    companion object {
        const val RECORDING_PAUSE = 0.9f
        const val DICTATION_PAUSE = 0.7f
    }

    /** Call before using the models, and [release] when done. */
    @Synchronized
    fun retain() {
        users++
        handler.removeCallbacks(unload)
    }

    /** @param keepWarmMs how long to hold the models once nobody is using them */
    @Synchronized
    fun release(keepWarmMs: Long = 0) {
        if (--users > 0) return
        users = 0
        handler.removeCallbacks(unload)
        if (keepWarmMs <= 0) unloadNow() else handler.postDelayed(unload, keepWarmMs)
    }

    @Synchronized
    private fun unloadNow() {
        if (users > 0) return
        synchronized(liveLock) {
            live?.second?.release()
            live = null
        }
        synchronized(refinerLock) {
            refiner?.second?.release()
            refiner = null
        }
    }
}
