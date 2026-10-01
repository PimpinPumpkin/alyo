package app.sotto.asr

import android.util.Log
import app.sotto.audio.AudioSpec
import app.sotto.data.Segment
import app.sotto.data.Word
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
    private val spool: File,
    scope: CoroutineScope,
    /** Called for every sentence pass two finishes, on the refine thread. */
    private val onRefined: (Segment) -> Unit = {},
) {
    private val liveThread = Executors.newSingleThreadExecutor { Thread(it, "sotto-live") }
    private val refineThread = Executors.newSingleThreadExecutor { Thread(it, "sotto-refine") }

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

    private class RefineJob(val id: Long, val fromSample: Long, val toSample: Long)

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
        audio.trySend(samples)
    }

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
        val recognizer = engine.live(liveSpec)
        val stream = recognizer.createStream()
        val out = BufferedOutputStream(FileOutputStream(spool), 64 * 1024)
        val bytes = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        val chunkSamples = liveSpec.chunkMs * AudioSpec.ASR_SAMPLE_RATE / 1000L

        var fed = 0L
        // The recognizer consumes audio a fixed chunk per decode step, and after a reset its token
        // times count from the first chunk it has not consumed yet. Counting steps is therefore
        // what pins a sentence to the right place on the recording's timeline.
        var consumed = 0L
        var segmentStart = 0L
        var nextId = 0L

        fun decodeReady() {
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
                consumed += chunkSamples
            }
        }

        fun currentWords(): List<Word> {
            val result = recognizer.getResult(stream)
            val start = sampleToMs(segmentStart)
            return wordsFromTokens(result.tokens, result.timestamps, null, start - liveSpec.lagMs, sampleToMs(fed))
                .map { if (it.startMs >= start) it else it.copy(startMs = start, endMs = maxOf(it.endMs, start + 1)) }
        }

        fun closeSegment(words: List<Word>) {
            val end = minOf(consumed, fed)
            if (words.isNotEmpty()) {
                val id = nextId++
                _segments.update { it + Segment(id, words) }
                _partial.value = emptyList()
                if (refinerSpec != null) {
                    out.flush()
                    // Give pass two a little air either side of the speech and none of the dead silence.
                    val from = maxOf(segmentStart, msToSample(words.first().startMs) - PAD_BEFORE)
                    val to = minOf(end, msToSample(words.last().endMs) + PAD_AFTER)
                    _pending.update { it + 1 }
                    refineQueue.trySend(RefineJob(id, from, maxOf(to, from)))
                }
            }
            recognizer.reset(stream)
            segmentStart = end
        }

        try {
            for (chunk in audio) {
                if (bytes.capacity() < chunk.size * 2) error("ASR chunk too large")
                bytes.clear()
                for (s in chunk) bytes.putShort((s * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                out.write(bytes.array(), 0, bytes.position())

                stream.acceptWaveform(chunk, AudioSpec.ASR_SAMPLE_RATE)
                fed += chunk.size
                decodeReady()
                val words = currentWords()
                if (recognizer.isEndpoint(stream)) closeSegment(words) else _partial.value = words
            }
            // Flush: the model needs trailing context before it will commit to the final word.
            stream.acceptWaveform(FloatArray(AudioSpec.ASR_SAMPLE_RATE * 3 / 5), AudioSpec.ASR_SAMPLE_RATE)
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
                    val refined = Segment(job.id, words.ifEmpty { current?.words.orEmpty() }, refined = true)
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
        // Generous on purpose. Pass one can miss a soft opening word entirely ("Okay", "I'll"), and
        // pass two can only recover it if the audio it is given starts before that word.
        const val PAD_BEFORE = AudioSpec.ASR_SAMPLE_RATE * 1L
        const val PAD_AFTER = AudioSpec.ASR_SAMPLE_RATE * 8 / 10L
        const val MIN_REFINE_SAMPLES = AudioSpec.ASR_SAMPLE_RATE / 5
    }
}
