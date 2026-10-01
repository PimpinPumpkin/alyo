package app.alyo.dictation

import android.util.Log
import app.alyo.asr.AsrEngine
import app.alyo.asr.ModelSpec
import app.alyo.asr.PauseFinder
import app.alyo.data.Word
import app.alyo.asr.wordsFromTokens
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * One stretch of voice typing, from the microphone opening to the last word being final.
 *
 * Two passes, as in a recorder. Pass one is a streaming model that turns audio into words as it
 * arrives; that is the text that appears while someone is still talking. Each time the speaker
 * pauses, the sentence is closed and queued for pass two: a larger model that hears the whole
 * sentence at once and rewrites it with punctuation and capitals.
 *
 * What the listener sees is a stream of [Listener.onText] calls: sentences that are final, and
 * the tail that is not yet (sentences waiting for pass two, then whatever is being said now).
 *
 * The microphone opens before the models have loaded and its audio is queued, so nothing said
 * during the load is lost. Audio is held in memory only until its sentence is final; nothing is
 * written to storage.
 */
class Dictation(
    private val engine: AsrEngine,
    private val liveSpec: ModelSpec,
    private val refinerSpec: ModelSpec?,
    private val continuers: Set<String>,
    private val source: () -> AudioSource,
    /** Stop by itself after this much silence. Zero or less: only [finish] stops it. */
    private val stopAfterSilenceMs: Int,
    /** Stop at the end of the first sentence, for callers that want one utterance. */
    private val singleUtterance: Boolean = false,
    private val keepWarmMs: Long,
    private val listener: Listener,
) {
    enum class State { LOADING, LISTENING, FINISHING, DONE }

    /** A finished sentence. [continues] is true when it was split off mid-flow rather than begun after a pause. */
    class Sentence(val words: List<Word>, val continues: Boolean)

    /** Called from the dictation's own threads, never two at once. */
    interface Listener {
        /**
         * @param final a sentence that will not change again, or null if only the tail moved
         * @param tail everything heard that is not final yet, as lower case words
         */
        fun onText(final: Sentence?, tail: String)
        fun onState(state: State)

        /** The microphone could not be opened or a model failed to load. [State.DONE] follows. */
        fun onError(message: String)
    }

    /** How loud the microphone is right now, 0 to 1. Read by the panel on every frame. */
    @Volatile var level = 0f
        private set

    private val audio = Channel<FloatArray>(Channel.UNLIMITED)
    private val refineQueue = Channel<RefineJob>(Channel.UNLIMITED)
    private val stopped = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    @Volatile private var discardRequested = false

    // Guards the tail and the order in which the listener hears about text.
    private val lock = Any()
    private val waiting = ArrayList<Pair<Long, String>>()
    private var partial = ""
    /** Sentences below this id were thrown away by [discardTail]; pass two's answer for them is dropped. */
    private var firstLiveId = 0L

    private class RefineJob(val id: Long, val samples: FloatArray, val offsetMs: Int, val live: List<Word>, val continues: Boolean)

    private val capture = thread(name = "alyo-capture", start = false) { runCapture() }
    private val liveThread = thread(name = "alyo-live", start = false) { runLive() }
    private val refineThread = thread(name = "alyo-refine", start = false) { runRefine() }

    fun start() {
        engine.retain()
        listener.onState(State.LOADING)
        capture.start()
        liveThread.start()
        refineThread.start()
    }

    /** Stop listening. What was said is still finished and delivered; [State.DONE] follows. */
    fun finish() {
        if (stopped.compareAndSet(false, true)) {
            if (!finished.get()) listener.onState(State.FINISHING)
        }
    }

    /** Throws away everything that is not final yet and carries on listening. */
    fun discardTail() {
        synchronized(lock) {
            firstLiveId = Long.MAX_VALUE // Until the live thread picks the request up and sets the real value.
            waiting.clear()
            partial = ""
            discardRequested = true
            listener.onText(null, "")
        }
    }

    /** Stop at once and deliver nothing more. */
    fun cancel() {
        synchronized(lock) { firstLiveId = Long.MAX_VALUE }
        cancelled = true
        finish()
    }

    @Volatile private var cancelled = false

    private fun runCapture() {
        val mic = try {
            source().also { it.start() }
        } catch (e: Throwable) {
            Log.e(TAG, "Could not open the microphone", e)
            listener.onError("The microphone is not available.")
            stopped.set(true)
            audio.close()
            return
        }
        try {
            val buffer = ShortArray(SAMPLE_RATE / 25) // 40 ms
            while (!stopped.get()) {
                val n = mic.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                val chunk = FloatArray(n)
                var energy = 0f
                for (i in 0 until n) {
                    val s = buffer[i] / 32768f
                    chunk[i] = s
                    energy += s * s
                }
                // Speech sits around 0.02 to 0.2 RMS; the square root spreads that over the meter.
                level = sqrt(sqrt(energy / n) * 4f).coerceIn(0f, 1f)
                audio.trySend(chunk)
            }
        } finally {
            level = 0f
            runCatching { mic.release() }
            audio.close()
        }
    }

    private fun tail(): String = (waiting.map { it.second } + partial).filter { it.isNotEmpty() }.joinToString(" ")

    private fun runLive() = runBlocking {
        try {
            val recognizer = engine.live(liveSpec, AsrEngine.DICTATION_PAUSE)
            if (!stopped.get()) listener.onState(State.LISTENING)
            val stream = recognizer.createStream()
            val chunkSamples = liveSpec.chunkMs * SAMPLE_RATE / 1000L

            // The audio not yet handed to pass two, and where on the timeline it begins.
            var pcm = FloatArray(SAMPLE_RATE * 16)
            var pcmBase = 0L
            var fed = 0L
            // The recognizer consumes audio a fixed chunk per decode step, and after a reset its
            // token times count from the first chunk it has not consumed yet. Counting steps is
            // therefore what pins a sentence to the right place on the timeline.
            var consumed = 0L
            var timeBase = 0L
            var audioStart = 0L
            // Words of the recognizer's current hypothesis that already went out in an earlier sentence.
            var claimed = 0
            var nextId = 0L
            var afterSplit = false
            val pauses = PauseFinder()
            // When the hypothesis last grew. Silence is measured from here, in samples.
            var lastSpeech = 0L
            var heardSpeech = false
            var lastText = ""
            var sentences = 0

            fun sampleToMs(sample: Long): Int = (sample * 1000 / SAMPLE_RATE).toInt()
            fun msToSample(ms: Int): Long = ms.toLong() * SAMPLE_RATE / 1000

            fun decodeReady() {
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                    consumed += chunkSamples
                }
            }

            fun currentWords(): List<Word> {
                val result = recognizer.getResult(stream)
                val start = sampleToMs(timeBase)
                return wordsFromTokens(result.tokens, result.timestamps, null, start - liveSpec.lagMs, sampleToMs(fed))
                    .map { if (it.startMs >= start) it else it.copy(startMs = start, endMs = maxOf(it.endMs, start + 1)) }
                    .drop(claimed)
            }

            fun slice(from: Long, to: Long): FloatArray {
                val a = (from - pcmBase).toInt().coerceIn(0, (fed - pcmBase).toInt())
                val b = (to - pcmBase).toInt().coerceIn(a, (fed - pcmBase).toInt())
                return pcm.copyOfRange(a, b)
            }

            /** Hands [words] on as a finished sentence whose audio runs from [audioStart] to [until]. */
            fun emit(words: List<Word>, until: Long) {
                if (words.isNotEmpty()) {
                    val id = nextId++
                    sentences++
                    // Give pass two a little air either side of the speech and none of the dead silence.
                    val from = maxOf(audioStart, msToSample(words.first().startMs) - PAD_BEFORE)
                    val to = maxOf(from, minOf(until, msToSample(words.last().endMs) + PAD_AFTER))
                    synchronized(lock) {
                        waiting += id to words.joinToString(" ") { it.text }
                        partial = ""
                        listener.onText(null, tail())
                    }
                    refineQueue.trySend(RefineJob(id, slice(from, to), sampleToMs(from), words, afterSplit))
                }
                audioStart = until
                // Everything before this is with pass two now.
                val drop = (until - pcmBase).toInt().coerceIn(0, (fed - pcmBase).toInt())
                if (drop > 0) {
                    System.arraycopy(pcm, drop, pcm, 0, (fed - pcmBase).toInt() - drop)
                    pcmBase += drop
                }
            }

            fun reset(end: Long) {
                recognizer.reset(stream)
                timeBase = end
                claimed = 0
                afterSplit = false
                lastText = ""
            }

            /** The speaker paused: close the sentence and start the recognizer afresh. */
            fun closeSentence(words: List<Word>) {
                val end = minOf(consumed, fed)
                emit(words, end)
                reset(end)
            }

            /**
             * Someone talking without a real pause would otherwise wait indefinitely for their
             * punctuation. Once a sentence has run long, look back for the breath in it (the
             * quietest quarter second) and split there. The recognizer is left running: only the
             * bookkeeping moves, so nothing it has heard is disturbed.
             *
             * @return the words left over after the split, or [words] unchanged if it is not time yet
             */
            fun splitIfLong(words: List<Word>): List<Word> {
                if (words.size < 2) return words
                val spoken = words.last().endMs - words.first().startMs
                if (spoken < SPLIT_AFTER_MS) return words
                val from = msToSample(words.first().startMs + SPLIT_MIN_HEAD_MS)
                val to = consumed - msToSample(liveSpec.lagMs + 300)
                val pause = pauses.quietest(from, to) ?: return words
                if (pause.depth > PAUSE_DEPTH && spoken < SPLIT_FORCE_MS) return words
                val cutMs = sampleToMs(pause.sample)
                val head = words.takeWhile { (it.startMs + it.endMs) / 2 < cutMs }
                if (head.isEmpty() || head.size == words.size) return words
                emit(head, pause.sample)
                afterSplit = true
                claimed += head.size
                return words.drop(head.size)
            }

            try {
                for (chunk in audio) {
                    if (cancelled) break
                    val have = (fed - pcmBase).toInt()
                    if (have + chunk.size > pcm.size) pcm = pcm.copyOf(maxOf(pcm.size * 2, have + chunk.size))
                    System.arraycopy(chunk, 0, pcm, have, chunk.size)
                    pauses.add(chunk)
                    stream.acceptWaveform(chunk, SAMPLE_RATE)
                    fed += chunk.size
                    decodeReady()

                    if (discardRequested) {
                        synchronized(lock) {
                            discardRequested = false
                            firstLiveId = nextId
                            // A sentence may have been closed between the request and now.
                            waiting.clear()
                            partial = ""
                            listener.onText(null, "")
                        }
                        val end = minOf(consumed, fed)
                        audioStart = end
                        reset(end)
                        lastSpeech = fed
                        continue
                    }

                    var words = currentWords()
                    val text = words.joinToString(" ") { it.text }
                    if (text.isNotEmpty() && text != lastText) {
                        lastSpeech = fed
                        heardSpeech = true
                    }
                    lastText = text
                    if (recognizer.isEndpoint(stream)) {
                        closeSentence(words)
                        if (singleUtterance && sentences > 0) finish()
                    } else {
                        words = splitIfLong(words)
                        synchronized(lock) {
                            val now = words.joinToString(" ") { it.text }
                            if (now != partial && !discardRequested) {
                                partial = now
                                listener.onText(null, tail())
                            }
                        }
                    }

                    // Someone who has not started yet gets longer than someone who has stopped.
                    val limit = if (heardSpeech) stopAfterSilenceMs else stopAfterSilenceMs + FIRST_WORD_GRACE_MS
                    if (stopAfterSilenceMs > 0 && sampleToMs(fed - lastSpeech) >= limit) finish()
                }
                if (!cancelled) {
                    // Flush: the model needs trailing context before it will commit to the final
                    // word, at least one whole step of it.
                    stream.acceptWaveform(FloatArray(maxOf(SAMPLE_RATE * 3 / 5, (chunkSamples + SAMPLE_RATE * 2 / 5).toInt())), SAMPLE_RATE)
                    stream.inputFinished()
                    decodeReady()
                    if (!discardRequested) closeSentence(currentWords())
                }
            } finally {
                stream.release()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Live pass failed", e)
            listener.onError("The speech model could not be loaded.")
            stopped.set(true)
        } finally {
            refineQueue.close()
        }
    }

    private fun runRefine() = runBlocking {
        try {
            // Loaded up front, alongside the live model: dictation always has a first sentence,
            // and it should not wait for this.
            val recognizer = refinerSpec?.let { spec -> runCatching { engine.refiner(spec) }.onFailure { Log.e(TAG, "Second pass unavailable", it) }.getOrNull() }
            for (job in refineQueue) {
                if (cancelled) continue
                var words = job.live
                if (recognizer != null && job.samples.size >= MIN_REFINE_SAMPLES && synchronized(lock) { job.id >= firstLiveId }) {
                    try {
                        val stream = recognizer.createStream()
                        val result = try {
                            stream.acceptWaveform(job.samples, SAMPLE_RATE)
                            recognizer.decode(stream)
                            recognizer.getResult(stream)
                        } finally {
                            stream.release()
                        }
                        val refined = wordsFromTokens(
                            result.tokens,
                            result.timestamps,
                            result.durations.takeIf { it.size == result.tokens.size },
                            job.offsetMs,
                            job.offsetMs + job.samples.size * 1000 / SAMPLE_RATE,
                        )
                        // An empty second opinion on something pass one heard is more likely a miss than a correction.
                        if (refined.isNotEmpty()) words = refined
                    } catch (e: Throwable) {
                        Log.e(TAG, "Could not refine sentence ${job.id}", e)
                    }
                }
                synchronized(lock) {
                    if (job.id >= firstLiveId && !cancelled) {
                        waiting.removeAll { it.first == job.id }
                        val continues = job.continues && words.firstOrNull()?.text?.lowercase()?.trim { !it.isLetter() } in continuers
                        listener.onText(Sentence(words, continues), tail())
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Refine pass failed", e)
        } finally {
            finished.set(true)
            engine.release(keepWarmMs)
            listener.onState(State.DONE)
        }
    }

    private companion object {
        const val TAG = "Dictation"
        // Generous on purpose. Pass one can miss a soft opening word entirely ("Okay", "I'll"), and
        // pass two can only recover it if the audio it is given starts before that word.
        const val PAD_BEFORE = SAMPLE_RATE * 1L
        const val PAD_AFTER = SAMPLE_RATE * 8 / 10L
        const val MIN_REFINE_SAMPLES = SAMPLE_RATE / 5

        // A sentence is split at a breath once it passes 8 s, and at the best available dip by 20 s.
        const val SPLIT_AFTER_MS = 8_000
        const val SPLIT_MIN_HEAD_MS = 3_000
        const val SPLIT_FORCE_MS = 20_000
        const val PAUSE_DEPTH = 0.12f

        const val FIRST_WORD_GRACE_MS = 2_000
    }
}
