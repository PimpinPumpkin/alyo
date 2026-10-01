package app.sotto.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.sotto.SottoApp
import app.sotto.data.RecordingMeta
import app.sotto.export.ExportFormat
import app.sotto.export.Exporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename recording") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRename(value.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onRename(value.text) }, enabled = value.text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DeleteDialog(title: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete recording?") },
        text = { Text("\"$title\" and its transcript will be removed from this phone. This can't be undone.") },
        confirmButton = {
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick what to export (audio, transcript, subtitles, word timings) and where it goes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(app: SottoApp, meta: RecordingMeta, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = remember { Exporter(context.applicationContext, app.store) }
    val hasTranscript = remember(meta.id) { !app.store.transcript(meta.id).value.isEmpty }
    var chosen by remember { mutableStateOf(if (hasTranscript) setOf(ExportFormat.AUDIO, ExportFormat.TEXT) else setOf(ExportFormat.AUDIO)) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Export", style = MaterialTheme.typography.headlineSmall)
            Text(meta.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            for (format in ExportFormat.entries) {
                val enabled = format == ExportFormat.AUDIO || hasTranscript
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { chosen = if (format in chosen) chosen - format else chosen + format }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = format in chosen, onCheckedChange = null, enabled = enabled)
                    Spacer(Modifier.padding(start = 16.dp))
                    Column {
                        Text(format.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (enabled) format.detail else "No transcript for this recording",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(56.dp),
                    onClick = {
                        scope.launch {
                            val count = withContext(Dispatchers.IO) { exporter.saveToDownloads(meta, chosen) }
                            Toast.makeText(context, if (count > 0) "Saved to Download/Sotto" else "Could not save", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    },
                ) {
                    Icon(Icons.Rounded.Download, null)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("Save to phone")
                }
                Button(
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(56.dp),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { exporter.share(meta, chosen) }
                            onDismiss()
                        }
                    },
                ) {
                    Icon(Icons.Rounded.IosShare, null)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("Share")
                }
            }
        }
    }
}
