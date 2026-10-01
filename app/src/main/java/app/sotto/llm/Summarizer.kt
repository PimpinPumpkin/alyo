package app.sotto.llm

import android.util.Log
import app.sotto.asr.ModelCatalog
import app.sotto.asr.ModelManager
import app.sotto.asr.ModelSpec
import app.sotto.data.RecordingStore
import app.sotto.data.Settings
import app.sotto.data.Summary
import app.sotto.ui.components.paragraphsOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/**
 * Writes summaries of recordings with a small language model, on the phone. One at a time: the
 * model takes around a gigabyte while it runs, and is unloaded as soon as the job ends.
 *
 * A transcript that fits the model's context is summarized in one pass. A longer one is read in
 * pieces, each boiled down to a few notes, and the summary is then written from the notes.
 */
class Summarizer(
    private val store: RecordingStore,
    private val models: ModelManager,
    private val settings: Settings,
    private val isRecording: () -> Boolean,
    private val scope: CoroutineScope,
) {
    /** @param fraction how far through reading the transcript, or null once it is writing */
    data class Progress(val fraction: Float?, val text: String = "")

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "sotto-summary") }.asCoroutineDispatcher()
    private val jobs = HashMap<String, Job>()
    @Volatile private var active: Llama? = null

    private val _running = MutableStateFlow<Map<String, Progress>>(emptyMap())
    /** Recordings being summarized right now (or waiting their turn), by id. */
    val running: StateFlow<Map<String, Progress>> = _running

    private val _failures = MutableStateFlow<Map<String, String>>(emptyMap())
    val failures: StateFlow<Map<String, String>> = _failures

    /** The model that will be used: the chosen one if it is installed, else any that is. */
    fun model(): ModelSpec? {
        val all = ModelCatalog.summarizers + ModelCatalog.importedSummarizer
        return all.firstOrNull { it.id == settings.summarizer.value && models.isInstalled(it) } ?: all.firstOrNull(models::isInstalled)
    }

    @Synchronized
    fun start(id: String) {
        if (jobs[id]?.isActive == true) return
        _failures.update { it - id }
        _running.update { it + (id to Progress(0f)) }
        jobs[id] = scope.launch(worker) {
            try {
                run(id)
            } catch (e: Throwable) {
                Log.e(TAG, "Summary failed", e)
                _failures.update { it + (id to (e.message ?: "Could not write a summary")) }
            } finally {
                _running.update { it - id }
            }
        }
    }

    @Synchronized
    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        active?.cancel()
    }

    private fun run(id: String) {
        val spec = model() ?: error("No summary model is installed")
        if (isRecording()) error("Finish recording first: there is not enough memory to do both")
        val parts = paragraphsOf(store.transcript(id).value.segments).map { it.text }
        if (parts.isEmpty()) error("There is no transcript to summarize")

        val llama = Llama.load(File(models.dir(spec), ModelCatalog.GGUF), CONTEXT, threads())
            ?: error("The summary model could not be loaded. It may be damaged or unsupported.")
        active = llama
        try {
            val chunks = chunk(parts, llama)
            var done = 0
            // What the listener reports, as a share of the whole job: reading every chunk is the
            // bulk of the time, so the bar tracks chunks read.
            fun listener(writing: Boolean) = object : Llama.Listener {
                override fun onReading(read: Int, total: Int) {
                    _running.update { it + (id to Progress((done + read.toFloat() / total) / (chunks.size + if (chunks.size > 1) 1 else 0).coerceAtLeast(1))) }
                }

                override fun onText(text: String) {
                    if (writing) _running.update { it + (id to Progress(null, text)) }
                }
            }

            val reply = if (chunks.size == 1) {
                llama.generate(SYSTEM, chunks[0], REPLY_TOKENS, OPENING, listener(writing = true))
            } else {
                val notes = StringBuilder()
                for (chunk in chunks) {
                    val part = llama.generate(NOTES_SYSTEM, chunk, NOTE_TOKENS, "- ", listener(writing = false)) ?: return
                    notes.append(part.trim()).append('\n')
                    done++
                }
                llama.generate(SYSTEM, "Notes taken from a long recording, in order:\n\n$notes", REPLY_TOKENS, OPENING, listener(writing = true))
            } ?: return

            val summary = parse(reply, "the ${spec.title} model")
            store.saveSummary(id, summary)
            // A model-written title beats the keyword guess, but never a name the user chose.
            if (settings.autoTitle.value && summary.title.isNotBlank()) store.rename(id, summary.title, auto = true)
        } finally {
            active = null
            llama.close()
        }
    }

    /** Packs paragraphs into pieces that each leave the model room to answer. */
    private fun chunk(paragraphs: List<String>, llama: Llama): List<String> {
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        var tokens = 0
        for (paragraph in paragraphs) {
            // A single enormous paragraph is split by sentences so nothing overflows.
            val pieces = if (llama.countTokens(paragraph) > CHUNK_TOKENS) paragraph.split(Regex("(?<=[.?!])\\s+")) else listOf(paragraph)
            for (piece in pieces) {
                val n = llama.countTokens(piece) + 2
                if (tokens + n > CHUNK_TOKENS && current.isNotEmpty()) {
                    chunks += current.toString()
                    current.clear()
                    tokens = 0
                }
                current.append(piece).append("\n\n")
                tokens += n
            }
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    // Big cores only: the little ones slow the whole thing down to their pace.
    private fun threads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    companion object {
        private const val TAG = "Summarizer"
        private const val CONTEXT = 4096
        private const val CHUNK_TOKENS = 3000
        private const val REPLY_TOKENS = 320
        private const val NOTE_TOKENS = 160
        private const val OPENING = "Title:"
        private const val MAX_TITLE_WORDS = 8

        // Spelled out as a form to fill in: the smallest models follow a shape far more reliably
        // than a description of one.
        private const val SYSTEM =
            "You summarize transcripts of voice recordings. Reply in exactly this form and nothing else:\n" +
                "Title: <what it is about, at most six words>\n" +
                "- <key point>\n" +
                "- <key point>\n" +
                "Give 2 to 5 key points covering the main facts, decisions and action items. Use only what is in the transcript."
        private const val NOTES_SYSTEM =
            "List the key points of this part of a transcript as 2 to 4 short bullet points, each starting with \"- \". " +
                "Use only what is in the text. No introduction."

        /** Turns the model's reply into a title and points, forgiving the ways small models stray from the format. */
        fun parse(reply: String, model: String): Summary {
            val lines = reply.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val bullet = Regex("^([-*•]|\\d+[.)])\\s+")
            fun clean(s: String) = s.replace("**", "").trim().trim('#', '"', ' ')
            val titleLine = lines.firstOrNull()?.takeIf { !bullet.containsMatchIn(it) }
            // A "title" that runs on is the model copying the opening sentence. Better none than that.
            val title = titleLine?.let { clean(it).removePrefix("Title:").trim().trimEnd('.', ':') }
                ?.takeIf { it.split(' ').size <= MAX_TITLE_WORDS }.orEmpty()
            val points = lines.drop(if (titleLine != null) 1 else 0).map { clean(it.replace(bullet, "")) }.filter { it.isNotEmpty() }
            return Summary(title, points, model)
        }
    }
}
