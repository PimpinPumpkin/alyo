package app.alyo.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.alyo.AlyoApp
import app.alyo.audio.M4a
import app.alyo.audio.Span
import app.alyo.audio.slice
import app.alyo.audio.totalMs
import app.alyo.data.keeping
import app.alyo.playback.Player
import app.alyo.ui.components.SeekBar
import app.alyo.ui.components.TapeWaveform
import app.alyo.ui.components.TranscriptView
import app.alyo.ui.components.ViewToggle
import app.alyo.ui.components.paragraphsOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MIN_SELECTION_MS = 200L

/**
 * Crop and remove, laid out like the player. Select a stretch by dragging the grips at either end
 * of the highlighted words in the transcript, or the frame's handles on the waveform or seek bar.
 * Play auditions just the selection. Crop keeps only the selection, Remove cuts it out. Edits stack up and can be undone, each one can be listened to,
 * and nothing touches the original: the result is saved as a copy.
 *
 * @param onClose called with the id of the new copy, or null if the user backed out
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CropScreen(app: AlyoApp, id: String, onClose: (copyId: String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val meta = remember(id) { app.store.meta(id) }
    if (meta == null) {
        LaunchedEffect(Unit) { onClose(null) }
        return
    }
    val originalPeaks = remember(id) { app.store.peaks(id) }
    val originalTranscript = remember(id) { app.store.transcript(id).value }

    // Each entry is what would be kept of the original after that edit; the last is the current state.
    var history by remember { mutableStateOf(listOf<List<Span>>()) }
    val keep = history.lastOrNull() ?: listOf(Span(0, meta.durationMs))
    val edited = history.isNotEmpty()
    val duration = keep.totalMs
    val peaks = remember(keep) { if (edited) originalPeaks.slice(keep) else originalPeaks }
    val originalSounds = remember(id) { app.store.sounds(id) }
    val sounds = remember(keep) { if (edited) originalSounds.keeping(keep) else originalSounds }
    val paragraphs = remember(keep) { paragraphsOf((if (edited) originalTranscript.keeping(keep) else originalTranscript).segments) }

    val player = remember(id) { Player(context.applicationContext) }
    DisposableEffect(player) { onDispose { player.release() } }
    var rendering by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    var start by remember { mutableLongStateOf(0L) }
    var end by remember { mutableLongStateOf(0L) }
    fun select(newStart: Long, newEnd: Long) {
        // Whichever edge moved is the one that gives way if they would cross.
        if (newStart != start) start = newStart.coerceIn(0, maxOf(0, end - MIN_SELECTION_MS))
        if (newEnd != end) end = newEnd.coerceIn(minOf(duration, start + MIN_SELECTION_MS), duration)
    }

    // After every edit: load audio that sounds like the result, and start a fresh selection.
    LaunchedEffect(keep) {
        player.pause()
        start = 0
        end = maxOf(duration / 4, minOf(duration, 1000))
        val audio = if (!edited) app.store.audio(id) else {
            rendering = true
            withContext(Dispatchers.IO) {
                File(context.cacheDir, "crop-preview-${history.size}.m4a").also { app.store.renderPreview(id, keep, it) }
            }
        }
        player.load(audio)
        rendering = false
    }

    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player, player.isPlaying, player.seekCount, player.durationMs) {
        position = player.positionMs
        while (player.isPlaying && isActive) {
            position = player.positionAt(withFrameNanos { it })
            // Play auditions the selection: it stops at the selection's end and rewinds to its start.
            if (position >= end) {
                player.pause()
                player.seekTo(start)
            }
        }
    }

    fun playSelection() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (position < start || position >= end - 50) player.seekTo(start)
            player.play()
        }
    }

    fun apply(next: List<Span>) {
        val snapped = next.map { Span(M4a.snapToFrame(it.startMs), M4a.snapToFrame(it.endMs)) }.filter { it.lengthMs > 0 }
        if (snapped.totalMs >= MIN_SELECTION_MS) history = history + listOf(snapped)
    }

    var showTranscript by rememberSaveable { mutableStateOf(false) }
    val wholeSelected = start <= 0 && end >= duration
    BackHandler { onClose(null) }

    Box(Modifier.fillMaxSize().background(colors.surface)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onClose(null) }) { Icon(Icons.Rounded.Close, "Close without saving") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { history = history.dropLast(1) }, enabled = edited && !saving) { Text("Undo") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = edited && !saving && !rendering,
                    onClick = {
                        saving = true
                        player.pause()
                        scope.launch {
                            val copy = withContext(Dispatchers.IO) { app.store.cut(id, keep, asCopy = true) }
                            onClose(copy)
                        }
                    },
                ) { Text("Save copy") }
            }

            AdaptivePane(content = {
            ContentCard(Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AnimatedContent(showTranscript, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { transcript ->
                            if (!transcript) {
                                TapeWaveform(
                                    peaks = peaks,
                                    peakCount = peaks.size,
                                    positionMs = { position },
                                    onScrub = { delta -> player.seekBy(delta) },
                                    selection = { start to end },
                                    onSelection = ::select,
                                    sounds = sounds,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else if (paragraphs.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("No transcript", style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
                                }
                            } else {
                                TranscriptView(
                                    paragraphs = paragraphs,
                                    positionMs = { position },
                                    selection = { start to end },
                                    // A tapped word pulls the nearer end of the selection to it.
                                    onWordTap = { word ->
                                        val middle = (word.startMs + word.endMs) / 2
                                        if (middle < (start + end) / 2) select(word.startMs.toLong(), end) else select(start, word.endMs.toLong())
                                    },
                                    onSelectionDrag = { startEdge, word ->
                                        if (startEdge) select(word.startMs.toLong(), end) else select(start, word.endMs.toLong())
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { apply(keep.slice(start, end)) },
                            enabled = !wholeSelected && !rendering && !saving,
                            modifier = Modifier.weight(1f).height(64.dp),
                        ) {
                            Icon(Icons.Rounded.Crop, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Crop", style = MaterialTheme.typography.titleMedium)
                        }
                        FilledTonalButton(
                            onClick = { apply(keep.slice(0, start) + keep.slice(end, duration)) },
                            enabled = !wholeSelected && !rendering && !saving,
                            modifier = Modifier.weight(1f).height(64.dp),
                        ) {
                            Icon(Icons.Rounded.ContentCut, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Remove", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }

            }, controls = {
            ViewToggle(showTranscript, { showTranscript = it }, Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp, bottom = 4.dp))
            SeekBar(
                peaks = peaks,
                durationMs = duration,
                positionMs = { position },
                onSeek = player::seekTo,
                selection = { start to end },
                onSelection = ::select,
                sounds = sounds,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            TimeLabels({ position }, duration)
            TransportRow(player, fine = true, onPlayPause = ::playSelection)
            })
        }

        if (saving || rendering) {
            Box(Modifier.fillMaxSize().background(colors.scrim.copy(alpha = if (saving) 0.4f else 0f)), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHighest) {
                    LoadingIndicator(Modifier.padding(24.dp).size(48.dp))
                }
            }
        }
    }
}
