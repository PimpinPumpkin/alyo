package app.alyo.asr

import android.os.Process
import android.util.Log
import app.alyo.audio.AudioSpec
import app.alyo.data.Segment
import app.alyo.data.Word
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Two-pass transcription of one recording, the same shape Google's recorder uses.
 *
 * Pass one is a streaming model that turns audio into words as it arrives; that is the text that
 * appears while someone is still talking. Each time the speaker pauses, the sentence is closed
 * into a [Segment] and queued for pass two: a larger model that hears the whole sentence at once
 * and rewrites it with punctuation, capitals and tighter word timings.
 *
 * The 16 kHz audio is spooled to [spool] so pass two can read any sentence back without holding
 * the whole recording in memory, however far it falls behind.
 */
class LiveTranscriber(
    private val engine: AsrEngine,
    private val liveSpec: ModelSpec,
    private val refinerSpec: ModelSpec?,
    private val continuers: Set<String>,
    private val spool: File,
    scope: CoroutineScope,
    /** Called for every sentence pass two finishes, on the refine thread. */
    private val onRefined: (Segment) -> Unit = {},
) {
    private val liveThread = Executors.newSingleThreadExecutor { Thread(it, "alyo-live") }
    private val refineThread = Executors.newSingleThreadExecutor { Thread(it, "alyo-refine") }

    private val audio = Channel<FloatArray>(Channel.UNLIMITED)
    private val refineQueue = Channel<RefineJob>(Channel.UNLIMITED)

    private val _segments = MutableStateFlow<List<Segment>>(emptyList())
    /** Finished sentences, oldest first. Entries are replaced in place when pass two rewrites them. */
    val segments: StateFlow<List<Segment>> = _segments

    private val _partial = MutableStateFlow<List<Word>>(emptyList())
    /** The sentence currently being spoken, as far as pass one has got. */
    val partial: StateFlow<List<Word>> = _partial

    private val _pending = MutableStateFlow(0)
    /** Sentences still waiting for pass two. */
    val pending: StateFlow<Int> = _pending

    private val liveDone = CompletableDeferred<Unit>()
    private val refineDone = CompletableDeferred<Unit>()

    @Volatile private var cancelled = false

    /** @param continues true when this piece was split off mid-flow rather than begun after a pause */
    private class RefineJob(val id: Long, val fromSample: Long, val toSample: Long, val continues: Boolean)

    init {
        scope.launch(liveThread.asCoroutineDispatcher()) {
            try {
                runLive()
            } catch (e: Throwable) {
                Log.e(TAG, "Live pass failed", e)
            } finally {
                refineQueue.close()
                liveDone.complete(Unit)
            }
        }
        scope.launch(refineThread.asCoroutineDispatcher()) {
            try {
                runRefine()
            } catch (e: Throwable) {
                Log.e(TAG, "Refine pass failed", e)
            } finally {
                _pending.value = 0
                refineDone.complete(Unit)
            }
        }
    }

    /** Hands over the next block of 16 kHz samples. Never blocks the caller. */
    fun accept(samples: FloatArray) {
        backlog.addAndGet(samples.size.toLong())
        audio.trySend(samples)
    }

    private val backlog = AtomicLong()
    /** Seconds of audio handed over but not yet through pass one. A file can be fed faster than it is heard; this is how the feeder knows to wait. */
    val backlogSeconds: Float get() = backlog.get().toFloat() / AudioSpec.ASR_SAMPLE_RATE

    /** No more audio is coming. Both passes drain what they have and then finish. */
    fun finish() {
        audio.close()
    }

    /** Resumes once every word has had its first pass, i.e. the transcript is complete if not yet polished. */
    suspend fun awaitLive() = liveDone.await()

    /** Resumes once pass two has caught up with everything. */
    suspend fun awaitRefined() = refineDone.await()

    /** Abandons the recording: pass two skips whatever it has not done yet. */
    fun cancel() {
        cancelled = true
        audio.close()
    }

    fun release() {
        liveThread.shutdown()
        refineThread.shutdown()
    }

    private suspend fun runLive() {
        // Below the interface, above pass two. Set before the model is loaded: the threads the
        // model starts for itself take on the priority of the thread that created it.
        Process.setThreadPriority(LIVE_PRIORITY)
        val recognizer = engine.live(liveSpec)
        var stream = recognizer.createStream()
        val out = BufferedOutputStream(FileOutputStream(spool), 64 * 1024)
        val bytes = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        val chunkSamples = liveSpec.chunkMs * AudioSpec.ASR_SAMPLE_RATE / 1000L

        var fed = 0L
        // The recognizer consumes audio a fixed chunk per decode step, and after a reset its token
        // times count from the first chunk it has not consumed yet. Counting steps is therefore
        // what pins a sentence to the right place on the recording's timeline.
        var consumed = 0L
        // Where the recognizer's clock was last reset, and where the audio not yet handed to pass
        // two begins. They differ after a split, which moves the second without touching the first.
        var timeBase = 0L
        var audioStart = 0L
        // Words of the recognizer's current hypothesis that already went out in an earlier segment.
        var claimed = 0
        var nextId = 0L
        val pauses = PauseFinder()
        // The audio since the last sentence ended, kept so that a recognizer started afresh can
        // be given again what the old one had only begun to hear. [recentBase] is where it starts.
        var recent = FloatArray(AudioSpec.ASR_SAMPLE_RATE * 16)
        var recentBase = 0L

        fun decodeReady() {
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
                consumed += chunkSamples
            }
        }

        // Set once the stray mark opening the current hypothesis has been handed back (see below).
        var strayReturned = false

        fun currentWords(): List<Word> {
            val result = recognizer.getResult(stream)
            val start = sampleToMs(timeBase)
            var words = wordsFromTokens(result.tokens, result.timestamps, null, start - liveSpec.lagMs, sampleToMs(fed))
                .map { if (it.startMs >= start) it else it.copy(startMs = start, endMs = maxOf(it.endMs, start + 1)) }
            // A model that punctuates can decide on the full stop only after the pause that closed
            // the sentence, so it arrives as the first "word" of the next one. It belongs on the
            // end of the sentence before.
            val stray = words.firstOrNull()?.text?.takeIf { text -> text.none { it.isLetterOrDigit() } }
            if (stray != null) {
                words = words.drop(1)
                if (!strayReturned) {
                    strayReturned = true
                    _segments.update { list ->
                        val last = list.lastOrNull()?.takeIf { !it.refined && !it.edited && it.words.isNotEmpty() } ?: return@update list
                        val word = last.words.last()
                        if (word.text.last().isLetterOrDigit()) list.dropLast(1) + last.copy(words = last.words.dropLast(1) + word.copy(text = word.text + stray)) else list
                    }
                }
            }
            return words.drop(claimed)
        }

        // Whether the next piece follows straight on from a split, with no pause in between.
        var afterSplit = false

        /** Publishes [words] as a finished sentence whose audio runs from [audioStart] to [until]. */
        fun emit(words: List<Word>, until: Long) {
            if (words.isNotEmpty()) {
                val id = nextId++
                _segments.update { it + Segment(id, words) }
                if (refinerSpec != null) {
                    out.flush()
                    // Give pass two a little air either side of the speech and none of the dead silence.
                    val from = maxOf(audioStart, msToSample(words.first().startMs) - PAD_BEFORE)
                    val to = minOf(until, msToSample(words.last().endMs) + PAD_AFTER)
                    _pending.update { it + 1 }
                    refineQueue.trySend(RefineJob(id, from, maxOf(to, from), continues = afterSplit))
                }
            }
            audioStart = until
            val drop = (until - recentBase).toInt().coerceIn(0, (fed - recentBase).toInt())
            if (drop > 0) {
                System.arraycopy(recent, drop, recent, 0, (fed - recentBase).toInt() - drop)
                recentBase += drop
            }
        }

        /**
         * The speaker paused: close the sentence and start the recognizer afresh.
         *
         * The recognizer reports a pause late, about a second after it began, and by then the
         * speaker may be talking again. Ending the sentence where the recognizer happens to be
         * can cut the next word in two, and a word cut in two is lost to both passes. So the
         * sentence ends inside the pause, and a fresh recognizer is given the audio from there.
         */
        fun closeSegment(words: List<Word>) {
            val heard = minOf(consumed, fed)
            val lastWord = words.lastOrNull()?.let { msToSample(it.endMs) } ?: audioStart
            val end = pauses.cutInPause(maxOf(lastWord, audioStart), heard, fed)
            // Anything the recognizer did get out of the speech after the pause will come again.
            emit(words.filter { msToSample((it.startMs + it.endMs) / 2) < end }, end)
            _partial.value = emptyList()
            stream.release()
            stream = recognizer.createStream()
            timeBase = end
            consumed = end
            claimed = 0
            afterSplit = false
            strayReturned = false
            val again = (fed - recentBase).toInt()
            if (again > 0) {
                stream.acceptWaveform(recent.copyOfRange(0, again), AudioSpec.ASR_SAMPLE_RATE)
                decodeReady()
            }
        }

        /**
         * Someone talking without a real pause would otherwise leave pass two waiting indefinitely,
         * or force a cut mid-word. Once a sentence has run long, look back for the breath in it
         * (the quietest quarter second) and split there. The recognizer is left running: only the
         * bookkeeping moves, so nothing it has heard is disturbed.
         *
         * @return the words left over after the split, or [words] unchanged if it is not time yet
         */
        fun splitIfLong(words: List<Word>): List<Word> {
            if (words.size < 2) return words
            val spoken = words.last().endMs - words.first().startMs
            if (spoken < SPLIT_AFTER_MS) return words
            // Search from a few seconds in (so the head is worth refining on its own) up to where
            // pass one's words are settled.
            val from = msToSample(words.first().startMs + SPLIT_MIN_HEAD_MS)
            val to = consumed - msToSample(liveSpec.lagMs + 300)
            val pause = pauses.quietest(from, to) ?: return words
            // Insist on a real dip, unless it has gone on so long that any dip will have to do.
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
                backlog.addAndGet(-chunk.size.toLong())
                if (bytes.capacity() < chunk.size * 2) error("ASR chunk too large")
                bytes.clear()
                for (s in chunk) bytes.putShort((s * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                out.write(bytes.array(), 0, bytes.position())
                pauses.add(chunk)
                val have = (fed - recentBase).toInt()
                if (have + chunk.size > recent.size) recent = recent.copyOf(maxOf(recent.size * 2, have + chunk.size))
                System.arraycopy(chunk, 0, recent, have, chunk.size)

                stream.acceptWaveform(chunk, AudioSpec.ASR_SAMPLE_RATE)
                fed += chunk.size
                decodeReady()
                val words = currentWords()
                if (recognizer.isEndpoint(stream)) closeSegment(words) else _partial.value = splitIfLong(words)
            }
            // Flush: the model needs trailing context before it will commit to the final word.
            // At least one whole step of silence, or a model that works in long steps never gets to
            // the audio still waiting for its step to fill.
            stream.acceptWaveform(FloatArray(maxOf(AudioSpec.ASR_SAMPLE_RATE * 3 / 5, (chunkSamples + AudioSpec.ASR_SAMPLE_RATE * 2 / 5).toInt())), AudioSpec.ASR_SAMPLE_RATE)
            stream.inputFinished()
            decodeReady()
            closeSegment(currentWords())
            _partial.value = emptyList()
        } finally {
            out.close()
            stream.release()
        }
    }

    private suspend fun runRefine() {
        val spec = refinerSpec ?: return
        // Pass two can always wait a moment; the screen and the live text cannot.
        Process.setThreadPriority(REFINE_PRIORITY)
        for (job in refineQueue) {
            try {
                if (cancelled) continue
                val samples = readSpool(job.fromSample, job.toSample)
                if (samples.size >= MIN_REFINE_SAMPLES) {
                    // Loaded on first use: a recording with no speech never pays for the big model.
                    val rec = engine.refiner(spec)
                    val stream = rec.createStream()
                    val result = try {
                        stream.acceptWaveform(samples, AudioSpec.ASR_SAMPLE_RATE)
                        rec.decode(stream)
                        rec.getResult(stream)
                    } finally {
                        stream.release()
                    }
                    val words = wordsFromTokens(
                        result.tokens,
                        result.timestamps,
                        result.durations.takeIf { it.size == result.tokens.size },
                        sampleToMs(job.fromSample),
                        sampleToMs(job.toSample),
                    )
                    val current = _segments.value.firstOrNull { it.id == job.id }
                    // An empty second opinion on something pass one heard is more likely a miss than a correction.
                    var refined = Segment(job.id, words.ifEmpty { current?.words.orEmpty() }, refined = true)
                    if (job.continues) stitch(job.id, refined)?.let { refined = it }
                    _segments.update { list -> list.map { if (it.id == job.id) refined else it } }
                    onRefined(refined)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Could not refine segment ${job.id}", e)
            } finally {
                _pending.update { maxOf(0, it - 1) }
            }
        }
    }

    /**
     * Undoes the false sentence break at a split: if [segment] opens with a continuing word, that
     * word loses its capital and the full stop ending the piece before it becomes a comma.
     *
     * @return [segment] with its first word lowered, or null if the two do not belong together
     */
    private fun stitch(id: Long, segment: Segment): Segment? {
        val first = segment.words.firstOrNull() ?: return null
        if (first.text.lowercase().trim { !it.isLetter() } !in continuers) return null
        val previous = _segments.value.firstOrNull { it.id == id - 1 }?.takeIf { it.refined } ?: return null
        val last = previous.words.lastOrNull()?.takeIf { it.text.endsWith(".") } ?: return null
        val joined = previous.copy(words = previous.words.dropLast(1) + last.copy(text = last.text.dropLast(1) + ","))
        _segments.update { list -> list.map { if (it.id == joined.id) joined else it } }
        onRefined(joined)
        return segment.copy(words = listOf(first.copy(text = first.text.replaceFirstChar { it.lowercase() })) + segment.words.drop(1))
    }

    private fun readSpool(fromSample: Long, toSample: Long): FloatArray {
        val count = (toSample - fromSample).toInt().coerceAtLeast(0)
        val raw = ByteArray(count * 2)
        val read = RandomAccessFile(spool, "r").use { file ->
            file.seek(fromSample * 2)
            var total = 0
            while (total < raw.size) {
                val n = file.read(raw, total, raw.size - total)
                if (n < 0) break
                total += n
            }
            total
        }
        val shorts = ByteBuffer.wrap(raw, 0, read - read % 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
    }

    private fun sampleToMs(sample: Long): Int = (sample * 1000 / AudioSpec.ASR_SAMPLE_RATE).toInt()
    private fun msToSample(ms: Int): Long = ms.toLong() * AudioSpec.ASR_SAMPLE_RATE / 1000

    private companion object {
        const val TAG = "LiveTranscriber"

        // Android "nice" values: 0 is an ordinary thread, higher gives way more readily. Both stay
        // under 10, where the system would confine the thread to the slow cores.
        const val LIVE_PRIORITY = 2
        const val REFINE_PRIORITY = 6
        // Generous on purpose. Pass one can miss a soft opening word entirely ("Okay", "I'll"), and
        // pass two can only recover it if the audio it is given starts before that word.
        const val PAD_BEFORE = AudioSpec.ASR_SAMPLE_RATE * 1L
        const val PAD_AFTER = AudioSpec.ASR_SAMPLE_RATE * 8 / 10L
        const val MIN_REFINE_SAMPLES = AudioSpec.ASR_SAMPLE_RATE / 5

        // A sentence is split at a breath once it passes 10 s, and at the best available dip by 24 s.
        const val SPLIT_AFTER_MS = 10_000
        const val SPLIT_MIN_HEAD_MS = 4_000
        const val SPLIT_FORCE_MS = 24_000
        const val PAUSE_DEPTH = 0.12f
    }
}
