package app.sotto.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sotto.SottoApp
import app.sotto.data.Transcript

/** Below this many words there is nothing to summarize: the recording is its own summary. About thirty seconds of talk. */
const val SUMMARY_MIN_WORDS = 80

fun Transcript.isWorthSummarizing(): Boolean = segments.sumOf { it.words.size } >= SUMMARY_MIN_WORDS

/**
 * The summary, living at the top of the transcript. Before there is one it is a single
 * "Summarize" button; while one is being written it shows progress and the words as they come;
 * afterwards it is the points themselves, with copy, share and redo.
 *
 * Nothing at all is shown for a recording too short to summarize, unless it already has a summary.
 *
 * @param onGetModel take the user to where a summary model can be downloaded or imported
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SummaryCard(app: SottoApp, id: String, transcript: Transcript, onGetModel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val running by app.summarizer.running.collectAsState()
    val failures by app.summarizer.failures.collectAsState()
    val summary by app.store.summary(id).collectAsState()
    val states by app.models.states.collectAsState()
    val progress = running[id]
    val current = summary
    // Re-read when installs change, so the button works the moment a model lands.
    val model = states.let { app.summarizer.model() }
    if (current == null && progress == null && !transcript.isWorthSummarizing()) return

    Column(modifier.fillMaxWidth().padding(bottom = 20.dp).animateContentSize()) {
        if (current == null && progress == null) {
            OutlinedButton(onClick = { if (model != null) app.summarizer.start(id) else onGetModel() }, shape = CircleShape) {
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (model != null) "Summarize" else "Get a model to summarize")
            }
            failures[id]?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.error, modifier = Modifier.padding(top = 8.dp)) }
            return@Column
        }
        Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp), tint = colors.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(if (progress != null) "Summarizing" else "Summary", style = MaterialTheme.typography.titleMedium, color = colors.primary, modifier = Modifier.weight(1f))
                    if (progress != null) {
                        TextButton(onClick = { app.summarizer.cancel(id) }) { Text("Stop") }
                    } else if (current != null) {
                        IconButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Summary", current.text)) }) {
                            Icon(Icons.Rounded.ContentCopy, "Copy summary", Modifier.size(20.dp))
                        }
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, current.text).putExtra(Intent.EXTRA_SUBJECT, current.title)
                            context.startActivity(Intent.createChooser(send, null))
                        }) { Icon(Icons.Rounded.Share, "Share summary", Modifier.size(20.dp)) }
                        IconButton(onClick = { app.summarizer.start(id) }, enabled = model != null) { Icon(Icons.Rounded.Refresh, "Write the summary again", Modifier.size(20.dp)) }
                    }
                }
                Column(Modifier.padding(end = 12.dp)) {
                    when {
                        progress?.fraction != null -> {
                            Spacer(Modifier.height(8.dp))
                            LinearWavyProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text("Reading the transcript. This runs on your phone, so long recordings take a while.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                        progress != null -> Text(progress.text, style = MaterialTheme.typography.bodyLarge)
                        current != null -> {
                            if (current.title.isNotBlank()) Text(current.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                            for (point in current.points) {
                                Row(Modifier.padding(bottom = 8.dp)) {
                                    Text("•", style = MaterialTheme.typography.bodyLarge, color = colors.primary, modifier = Modifier.width(18.dp))
                                    Text(point, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            Text("Experimental. Written on this phone by ${current.model.ifBlank { "a small model" }}, which can get things wrong.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
