package app.sotto.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sotto.SottoApp

/** The button above the transcript: "Summarize" until there is a summary, then "Summary". */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SummaryChip(app: SottoApp, id: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val running by app.summarizer.running.collectAsState()
    val summary by app.store.summary(id).collectAsState()
    val progress = running[id]
    OutlinedButton(onClick = onClick, shape = CircleShape, modifier = modifier) {
        if (progress != null) LoadingIndicator(Modifier.size(20.dp)) else Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                progress?.fraction != null -> "Reading  ${(progress.fraction * 100).toInt()}%"
                progress != null -> "Writing"
                summary != null -> "Summary"
                else -> "Summarize"
            },
        )
    }
}

/**
 * Shows the summary, or the progress toward one, or how to get the model that writes them.
 *
 * @param onGetModel take the user to where a summary model can be downloaded or imported
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SummarySheet(app: SottoApp, id: String, onDismiss: () -> Unit, onGetModel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val running by app.summarizer.running.collectAsState()
    val failures by app.summarizer.failures.collectAsState()
    val summary by app.store.summary(id).collectAsState()
    val states by app.models.states.collectAsState()
    val progress = running[id]
    // Re-read when installs change, so the sheet updates if a model lands while it is open.
    val model = states.let { app.summarizer.model() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            val current = summary
            when {
                progress != null -> {
                    Text("Summarizing", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    if (progress.fraction != null) {
                        LinearWavyProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Text("Reading the transcript. This runs on your phone, so long recordings take a while.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    } else {
                        Text(progress.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(20.dp))
                    TextButton(onClick = { app.summarizer.cancel(id) }, modifier = Modifier.align(Alignment.End)) { Text("Stop") }
                }
                current != null -> {
                    Text(current.title.ifBlank { "Summary" }, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        if (current.points.isEmpty()) {
                            Text("This one is short enough to read as it is, so it only got a title.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 12.dp))
                        }
                        for (point in current.points) {
                            Row(Modifier.padding(bottom = 12.dp)) {
                                Text("•", style = MaterialTheme.typography.bodyLarge, color = colors.primary, modifier = Modifier.width(20.dp))
                                Text(point, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        Text(
                            "Written on this phone by ${current.model.ifBlank { "a small language model" }}. Small models make mistakes: check anything important against the transcript.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(
                            onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Summary", current.text)) },
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) {
                            Icon(Icons.Rounded.ContentCopy, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Copy")
                        }
                        FilledTonalButton(onClick = { app.summarizer.start(id) }, enabled = model != null, modifier = Modifier.weight(1f).height(56.dp)) {
                            Icon(Icons.Rounded.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Redo")
                        }
                    }
                }
                model == null -> {
                    Text("Summaries", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "A small language model can read a transcript and write a title and the key points, all on this phone. It is a separate download.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onGetModel, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Choose a summary model") }
                }
                else -> {
                    Text("Summarize", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    failures[id]?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.error, modifier = Modifier.padding(bottom = 12.dp)) }
                    Text("Uses ${model.title}. Nothing leaves the phone.", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { app.summarizer.start(id) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.Rounded.AutoAwesome, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Summarize")
                    }
                }
            }
        }
    }
}
