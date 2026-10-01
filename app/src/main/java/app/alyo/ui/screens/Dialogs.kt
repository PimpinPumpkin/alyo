package app.alyo.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.alyo.AlyoApp
import app.alyo.data.RecordingMeta
import app.alyo.export.ExportFormat
import app.alyo.export.Exporter
import app.alyo.ui.components.SheetGroup
import app.alyo.ui.components.SheetRow
import app.alyo.ui.components.paragraphsOf
import app.alyo.ui.formatWhen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Title editing as a card that drops over the top of the screen, dimming everything else: a close
 * button, Save, and the title as large editable text. Place it last in a full-size Box.
 */
@Composable
fun EditTitleOverlay(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var value by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = colors.surfaceContainerHigh,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .fillMaxWidth()
                // Swallow taps so touching the card does not dismiss it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Cancel") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onSave(value.text) }, enabled = value.text.isNotBlank()) { Text("Save") }
                }
                Spacer(Modifier.height(20.dp))
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = MaterialTheme.typography.headlineMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (value.text.isNotBlank()) onSave(value.text) }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
                )
            }
        }
    }
}

/** A one-line text prompt: a title, a field, Save and Cancel. */
@Composable
fun NameDialog(title: String, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (value.text.isNotBlank()) onSave(value.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value.text) }, enabled = value.text.isNotBlank()) { Text("Save") } },
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

/**
 * The sheet behind the three-dot button: the recording's name and date, then its actions in
 * grouped blocks. [extra] is a second block for things only the player offers.
 *
 * @param onCrop null where cropping is not available (still polishing the transcript)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingMenuSheet(
    meta: RecordingMeta,
    onDismiss: () -> Unit,
    onEditTitle: () -> Unit,
    onShare: () -> Unit,
    onCrop: (() -> Unit)?,
    onDelete: () -> Unit,
    extra: List<SheetRow> = emptyList(),
) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(meta.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
            }
            Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(formatWhen(meta.createdAt), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
            SheetGroup(
                listOf(
                    SheetRow(Icons.Rounded.Edit, "Edit title", onClick = onEditTitle),
                    SheetRow(Icons.Rounded.Share, "Share", onClick = onShare),
                    SheetRow(Icons.Rounded.ContentCut, "Crop & remove", enabled = onCrop != null, onClick = { onCrop?.invoke() }),
                    SheetRow(Icons.Rounded.Delete, "Delete", onClick = onDelete),
                ),
            )
            if (extra.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SheetGroup(extra)
            }
        }
    }
}

/** Pick what to share (audio, transcript, subtitles, word timings) and where it goes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(app: AlyoApp, meta: RecordingMeta, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val exporter = remember { Exporter(context.applicationContext, app.store) }
    val hasTranscript = remember(meta.id) { !app.store.transcript(meta.id).value.isEmpty }
    var chosen by remember { mutableStateOf(if (hasTranscript) setOf(ExportFormat.AUDIO, ExportFormat.TEXT) else setOf(ExportFormat.AUDIO)) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Share", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp))
            SheetGroup(
                ExportFormat.entries.map { format ->
                    val available = format == ExportFormat.AUDIO || hasTranscript
                    SheetRow(
                        icon = when (format) {
                            ExportFormat.AUDIO -> Icons.Rounded.AudioFile
                            ExportFormat.TEXT -> Icons.Rounded.Description
                            ExportFormat.SRT -> Icons.Rounded.Subtitles
                            ExportFormat.JSON -> Icons.Rounded.DataObject
                        },
                        label = format.label,
                        detail = if (available) format.detail else "No transcript for this recording",
                        enabled = available,
                        trailing = { Checkbox(checked = format in chosen, onCheckedChange = null, enabled = available) },
                        onClick = { chosen = if (format in chosen) chosen - format else chosen + format },
                    )
                },
            )
            if (hasTranscript) {
                Spacer(Modifier.height(12.dp))
                SheetGroup(
                    listOf(
                        SheetRow(Icons.Rounded.ContentCopy, "Copy transcript", onClick = {
                            val text = paragraphsOf(app.store.transcript(meta.id).value.segments).joinToString("\n\n") { it.text }
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(meta.title, text))
                            onDismiss()
                        }),
                    ),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(64.dp),
                    onClick = {
                        scope.launch {
                            val count = withContext(Dispatchers.IO) { exporter.saveToDownloads(meta, chosen) }
                            Toast.makeText(context, if (count > 0) "Saved to Download/Alyo" else "Could not save", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    },
                ) {
                    Icon(Icons.Rounded.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save to phone")
                }
                Button(
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(64.dp),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { exporter.share(meta, chosen) }
                            onDismiss()
                        }
                    },
                ) {
                    Icon(Icons.Rounded.Share, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
            }
        }
    }
}
