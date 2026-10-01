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
 * Listens for what is not speech. Once a second, the last two seconds are classified against the
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
    /** Music and the like so far. While recording, the newest stretch is provisional and can still change. */
    val spans: StateFlow<List<SoundSpan>> = _spans

    private val _hearing = MutableStateFlow<String?>(null)
    /** What is being heard this second: [SPEECH], one of the [SoundSpan] kinds, or null for quiet. */
    val hearing: StateFlow<String?> = _hearing

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
            // Two seconds are judged at a time, but a judgment is made every second, on the two
            // seconds just gone. That halves the wait before a change shows on screen.
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
                        val heard = classify(tagger, window)
                        _hearing.value = heard
                        kinds += heard.takeIf { it != SPEECH }
                        _spans.value = spansOf(kinds, provisional = true)
                        // Keep the newer half as the start of the next window.
                        window.copyInto(window, 0, HOP, WINDOW)
                        filled = WINDOW - HOP
                    }
                }
            }
            _spans.value = spansOf(kinds, provisional = false)
            _hearing.value = null
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
            speech >= THRESHOLD -> SPEECH
            else -> null
        }
    }

    companion object {
        private const val TAG = "SoundTagger"
        /** Reported by [hearing] only; speech is the default and is never stored as a span. */
        const val SPEECH = "Speech"

        private const val WINDOW_MS = 2000
        private const val HOP_MS = 1000
        private const val WINDOW = AudioSpec.ASR_SAMPLE_RATE * WINDOW_MS / 1000
        private const val HOP = AudioSpec.ASR_SAMPLE_RATE * HOP_MS / 1000
        private const val THRESHOLD = 0.4f

        /** Music has to last this many one-second steps to be kept: less is as likely a ringtone or a door chime as a song. */
        private const val MIN_MUSIC = 3

        /**
         * Joins the once-a-second verdicts into spans. A single stray verdict in the middle of a
         * run is absorbed. Short music is dropped, except that while recording ([provisional])
         * a run still in progress at the end is shown at once, since it may yet grow.
         */
        fun spansOf(kinds: List<String?>, provisional: Boolean = false): List<SoundSpan> {
            val smooth = kinds.toMutableList()
            for (i in 1 until smooth.size - 1) {
                if (smooth[i] != smooth[i - 1] && smooth[i - 1] != null && smooth[i - 1] == smooth[i + 1]) smooth[i] = smooth[i - 1]
            }
            val out = ArrayList<SoundSpan>()
            var start = 0
            for (i in 1..smooth.size) {
                if (i == smooth.size || smooth[i] != smooth[start]) {
                    val kind = smooth[start]
                    val steps = i - start
                    val growing = provisional && i == smooth.size
                    // Verdict n is about the two seconds ending at second n + 2, so a run of them
                    // covers from its first second to one second past its last.
                    if (kind != null && (kind != SoundSpan.MUSIC || steps >= MIN_MUSIC || growing)) {
                        out += SoundSpan(kind, start * HOP_MS, i * HOP_MS + (WINDOW_MS - HOP_MS))
                    }
                    start = i
                }
            }
            return out
        }
    }
}
