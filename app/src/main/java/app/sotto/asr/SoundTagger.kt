package app.sotto.asr

import android.content.res.AssetManager
import android.util.Log
import app.sotto.audio.AudioSpec
import app.sotto.data.SoundSpan
import com.k2fsa.sherpa.onnx.AudioTagging
import com.k2fsa.sherpa.onnx.AudioTaggingConfig
import com.k2fsa.sherpa.onnx.AudioTaggingModelConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Listens for what is not speech. The recording is classified two seconds at a time against the
 * AudioSet sound classes, and runs of music, applause or laughter become [SoundSpan]s, so the
 * waveform can show them and the transcript can say "Music" where there were no words to write.
 *
 * The model is bundled with the app, so this works even with no speech model installed.
 */
class SoundTagger(private val assets: AssetManager, scope: CoroutineScope) {
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "sotto-sounds").apply { priority = Thread.MIN_PRIORITY } }
    private val audio = Channel<FloatArray>(Channel.UNLIMITED)
    private val done = CompletableDeferred<Unit>()

    private val _spans = MutableStateFlow<List<SoundSpan>>(emptyList())
    val spans: StateFlow<List<SoundSpan>> = _spans

    init {
        scope.launch(thread.asCoroutineDispatcher()) {
            try {
                run()
            } catch (e: Throwable) {
                Log.e(TAG, "Sound tagging failed", e)
            } finally {
                done.complete(Unit)
                thread.shutdown()
            }
        }
    }

    fun accept(samples: FloatArray) {
        audio.trySend(samples)
    }

    fun finish() {
        audio.close()
    }

    suspend fun await() = done.await()

    private suspend fun run() {
        val tagger = AudioTagging(
            assets,
            AudioTaggingConfig(
                model = AudioTaggingModelConfig(ced = "sounds/model.int8.onnx", numThreads = 1),
                labels = "sounds/class_labels_indices.csv",
                topK = 6,
            ),
        )
        try {
            val window = FloatArray(WINDOW)
            var filled = 0
            val kinds = ArrayList<String?>()
            for (chunk in audio) {
                var offset = 0
                while (offset < chunk.size) {
                    val n = minOf(chunk.size - offset, WINDOW - filled)
                    chunk.copyInto(window, filled, offset, offset + n)
                    filled += n
                    offset += n
                    if (filled == WINDOW) {
                        kinds += classify(tagger, window)
                        filled = 0
                        _spans.value = spansOf(kinds)
                    }
                }
            }
        } finally {
            tagger.release()
        }
    }

    private fun classify(tagger: AudioTagging, window: FloatArray): String? {
        val stream = tagger.createStream()
        val events = try {
            stream.acceptWaveform(window, AudioSpec.ASR_SAMPLE_RATE)
            tagger.compute(stream, 6)
        } finally {
            stream.release()
        }
        fun score(vararg names: String) = events.filter { it.name in names }.maxOfOrNull { it.prob } ?: 0f
        val speech = score("Speech", "Conversation", "Narration, monologue")
        val music = score("Music", "Musical instrument", "Singing", "Song")
        return when {
            // Talking over background music is still, for a transcript's purposes, talking.
            music >= THRESHOLD && music > speech -> SoundSpan.MUSIC
            score("Applause", "Clapping") >= THRESHOLD -> SoundSpan.APPLAUSE
            score("Laughter") >= THRESHOLD && score("Laughter") > speech -> SoundSpan.LAUGHTER
            else -> null
        }
    }

    companion object {
        private const val TAG = "SoundTagger"
        private const val WINDOW_MS = 2000
        private const val WINDOW = AudioSpec.ASR_SAMPLE_RATE * WINDOW_MS / 1000
        private const val THRESHOLD = 0.4f

        /**
         * Joins per-window verdicts into spans. A single stray window in the middle of a run is
         * absorbed, and music has to last two windows to count: one window of something tonal is
         * as likely a ringtone or a door chime as a song.
         */
        fun spansOf(kinds: List<String?>): List<SoundSpan> {
            val smooth = kinds.toMutableList()
            for (i in 1 until smooth.size - 1) {
                if (smooth[i] != smooth[i - 1] && smooth[i - 1] != null && smooth[i - 1] == smooth[i + 1]) smooth[i] = smooth[i - 1]
            }
            val out = ArrayList<SoundSpan>()
            var start = 0
            for (i in 1..smooth.size) {
                if (i == smooth.size || smooth[i] != smooth[start]) {
                    val kind = smooth[start]
                    val windows = i - start
                    if (kind != null && (kind != SoundSpan.MUSIC || windows >= 2)) out += SoundSpan(kind, start * WINDOW_MS, i * WINDOW_MS)
                    start = i
                }
            }
            return out
        }
    }
}
