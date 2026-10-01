package app.sotto.asr

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
 * Owns the loaded speech models. Loading takes seconds and hundreds of megabytes, so each model is
 * loaded once and shared between overlapping recordings, and dropped when the last one is done.
 */
class AsrEngine(private val models: ModelManager) {
    private var live: Pair<String, OnlineRecognizer>? = null
    private var refiner: Pair<String, OfflineRecognizer>? = null

    @Synchronized
    fun live(spec: ModelSpec): OnlineRecognizer {
        live?.let { (id, recognizer) -> if (id == spec.id) return recognizer else recognizer.release() }
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
                rule2 = EndpointRule(true, 0.9f, 0f),
                // Long unbroken speech is split at breaths by LiveTranscriber, which can pick the
                // moment; this blunt cut is only a backstop so the hypothesis cannot grow forever.
                rule3 = EndpointRule(false, 0f, 120f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        return OnlineRecognizer(null, config).also { live = spec.id to it }
    }

    @Synchronized
    fun refiner(spec: ModelSpec): OfflineRecognizer {
        refiner?.let { (id, recognizer) -> if (id == spec.id) return recognizer else recognizer.release() }
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
        return OfflineRecognizer(null, config).also { refiner = spec.id to it }
    }

    /**
     * Frees both models. Only safe when no transcriber is running; [app.sotto.service.RecorderController]
     * calls it once the last recording has finished its second pass.
     */
    @Synchronized
    fun releaseAll() {
        live?.second?.release()
        live = null
        refiner?.second?.release()
        refiner = null
    }
}
