package app.sotto.asr

import android.content.Context
import app.sotto.audio.AudioSpec
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One stretch of one person talking. */
data class SpeakerTurn(val speaker: Int, val startMs: Int, val endMs: Int)

/**
 * Tells voices apart. A segmentation model finds where the speaker changes, a second model turns
 * each stretch into a fingerprint of the voice, and stretches with similar fingerprints are
 * grouped as the same person. It runs once over a whole recording, after the fact.
 */
class Diarizer(private val context: Context, private val models: ModelManager) {
    private val spec: ModelSpec? get() = models.usable(ModelCatalog.speakers)

    fun isReady(): Boolean = spec != null

    /**
     * @param spool 16 kHz mono 16-bit audio, as [LiveTranscriber] writes it
     * @return who spoke when, or nothing if the recording is too long to hold in memory at once
     */
    fun turns(spool: File): List<SpeakerTurn> {
        if (!isReady() || !spool.exists() || spool.length() > MAX_BYTES) return emptyList()
        val samples = read(spool)
        if (samples.size < AudioSpec.ASR_SAMPLE_RATE * 2) return emptyList()
        val spec = spec ?: return emptyList()
        val diarization = OfflineSpeakerDiarization(
            null,
            OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentationModel().path),
                    numThreads = 2,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(model = File(models.dir(spec), spec.encoder).path, numThreads = 2),
                // The number of people is not known in advance, so voices are merged by similarity.
                clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
                minDurationOn = 0.3f,
                minDurationOff = 0.5f,
            ),
        )
        return try {
            diarization.process(samples).map { SpeakerTurn(it.speaker, (it.start * 1000).toInt(), (it.end * 1000).toInt()) }
        } finally {
            diarization.release()
        }
    }

    /** The segmentation model ships inside the APK; the library wants a file path, so it is copied out once. */
    private fun segmentationModel(): File {
        val file = File(context.filesDir, "speakers-segmentation.onnx")
        if (!file.exists() || file.length() == 0L) {
            context.assets.open("speakers/model.int8.onnx").use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return file
    }

    private fun read(spool: File): FloatArray {
        val bytes = ByteArray(spool.length().toInt() and 1.inv())
        RandomAccessFile(spool, "r").use { it.readFully(bytes) }
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
    }

    private companion object {
        // 45 minutes of 16 kHz audio. Beyond that the whole recording no longer fits comfortably
        // in memory as floats, which is how the library needs it.
        const val MAX_BYTES = 45L * 60 * AudioSpec.ASR_SAMPLE_RATE * 2
    }
}
