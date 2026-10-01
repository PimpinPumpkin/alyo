package app.sotto.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.sotto.data.RecordingMeta
import app.sotto.data.RecordingStore
import app.sotto.data.Transcript
import app.sotto.data.Word
import app.sotto.ui.formatClock
import app.sotto.ui.components.paragraphsOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val label: String, val detail: String, val extension: String, val mime: String) {
    AUDIO("Audio", "M4A, AAC 48 kHz", "m4a", "audio/mp4"),
    TEXT("Transcript", "Plain text with timestamps", "txt", "text/plain"),
    SRT("Subtitles", "SRT, timed line by line", "srt", "application/x-subrip"),
    JSON("Word timings", "JSON, every word with start and end", "json", "application/json"),
}

/** Turns a recording into files other apps can take: via the share sheet or straight into Downloads. */
class Exporter(private val context: Context, private val store: RecordingStore) {
    private val exports = File(context.cacheDir, "exports")

    /** Opens the system share sheet with the chosen files. */
    fun share(meta: RecordingMeta, formats: Collection<ExportFormat>) {
        val files = formats.map { render(meta, it) }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, context.packageName + ".files", it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(formats.first().mime).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.putExtra(Intent.EXTRA_SUBJECT, meta.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copies the chosen files into `Download/Sotto/`. Returns how many were written. */
    fun saveToDownloads(meta: RecordingMeta, formats: Collection<ExportFormat>): Int {
        var written = 0
        for (format in formats) {
            val file = render(meta, format)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, format.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Sotto")
            }
            val uri: Uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
            context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: continue
            written++
        }
        return written
    }

    private fun render(meta: RecordingMeta, format: ExportFormat): File {
        exports.mkdirs()
        val target = File(exports, safeName(meta.title) + "." + format.extension)
        val transcript = store.transcript(meta.id).value
        when (format) {
            ExportFormat.AUDIO -> store.audio(meta.id).copyTo(target, overwrite = true)
            ExportFormat.TEXT -> target.writeText(text(meta, transcript))
            ExportFormat.SRT -> target.writeText(srt(transcript))
            ExportFormat.JSON -> target.writeText(json(meta, transcript))
        }
        return target
    }

    private fun text(meta: RecordingMeta, transcript: Transcript): String = buildString {
        val date = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date(meta.createdAt))
        append(meta.title).append('\n')
        append(date).append("  ·  ").append(formatClock(meta.durationMs)).append("\n")
        for (paragraph in paragraphsOf(transcript.segments)) {
            append("\n[").append(formatClock(paragraph.startMs.toLong())).append("]\n")
            append(paragraph.text).append('\n')
        }
    }

    /** One cue per short run of words, broken at sentence ends, pauses, or when a line gets long. */
    private fun srt(transcript: Transcript): String = buildString {
        var index = 1
        val cue = ArrayList<Word>()
        fun flush() {
            if (cue.isEmpty()) return
            append(index++).append('\n')
            append(srtTime(cue.first().startMs)).append(" --> ").append(srtTime(cue.last().endMs)).append('\n')
            append(cue.joinToString(" ") { it.text }).append("\n\n")
            cue.clear()
        }
        for (segment in transcript.segments) {
            for (word in segment.words) {
                val gap = if (cue.isEmpty()) 0 else word.startMs - cue.last().endMs
                val length = cue.sumOf { it.text.length + 1 }
                if (gap > 700 || length + word.text.length > 64) flush()
                cue += word
                if (word.text.lastOrNull() in SENTENCE_END && length > 24) flush()
            }
            flush()
        }
    }

    private fun json(meta: RecordingMeta, transcript: Transcript): String = buildString {
        fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        append("{\n  \"title\": ").append(quote(meta.title))
        append(",\n  \"recordedAt\": ").append(meta.createdAt)
        append(",\n  \"durationMs\": ").append(meta.durationMs)
        append(",\n  \"language\": ").append(quote(transcript.language))
        append(",\n  \"words\": [")
        var first = true
        for (segment in transcript.segments) for (word in segment.words) {
            append(if (first) "\n" else ",\n")
            first = false
            append("    {\"text\": ").append(quote(word.text)).append(", \"startMs\": ").append(word.startMs).append(", \"endMs\": ").append(word.endMs).append('}')
        }
        append("\n  ]\n}\n")
    }

    private fun srtTime(ms: Int): String = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    private fun safeName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "Recording" }

    private companion object {
        val SENTENCE_END = setOf('.', '?', '!')
    }
}
