package app.sotto.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.sotto.SottoApp
import app.sotto.audio.Peaks
import app.sotto.audio.Span
import app.sotto.playback.Player
import app.sotto.ui.components.OverviewWaveform
import app.sotto.ui.formatClock
import app.sotto.ui.formatClockFine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val MIN_SELECTION_MS = 300L
private const val NUDGE_MS = 100L

private enum class CutKind(val title: String, val detail: String) {
    CROP("Keep only the selection?", "Everything outside it is removed."),
    REMOVE("Cut out the selection?", "What's before and after is joined together."),
}

/**
 * Crop a recording to a selected stretch, or cut that stretch out. The transcript follows the
 * audio: words in removed audio go, and the rest are re-timed.
 *
 * @param onClose called with the id of the recording to show next, or null if nothing changed
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TrimScreen(app: SottoApp, id: String, onClose: (resultId: String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val recordings by app.store.recordings.collectAsState()
    val meta = recordings.firstOrNull { it.id == id }
    if (meta == null) {
        LaunchedEffect(Unit) { onClose(null) }
        return
    }
    val duration = meta.durationMs
    val peaks = remember(id, duration) { app.store.peaks(id) }
    val player = remember(id) { Player(context.applicationContext) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player) { player.load(app.store.audio(id)) }

    var start by remember(duration) { mutableLongStateOf((duration * 0.2).toLong()) }
    var end by remember(duration) { mutableLongStateOf((duration * 0.8).toLong()) }
    var position by remember { mutableLongStateOf(0L) }
    var pending by remember { mutableStateOf<CutKind?>(null) }
    var working by remember { mutableStateOf(false) }

    // Preview plays the selection and stops at its end.
    LaunchedEffect(player, player.isPlaying, player.seekCount) {
        position = player.positionMs
        while (player.isPlaying && isActive) {
            withFrameNanos { }
            position = player.positionMs
            if (position >= end) {
                player.pause()
                player.seekTo(start)
            }
        }
    }

    fun setStart(ms: Long) { start = ms.coerceIn(0, end - MIN_SELECTION_MS) }
    fun setEnd(ms: Long) { end = ms.coerceIn(start + MIN_SELECTION_MS, duration) }

    Column(Modifier.fillMaxSize().background(colors.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onClose(null) }) { Icon(Icons.Rounded.Close, "Close without changes") }
            Text("Crop or cut", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }

        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
            Text(
                formatClock(end - start),
                style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "selected of ${formatClock(duration)}",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(32.dp))

            SelectionWaveform(
                peaks = peaks,
                durationMs = duration,
                start = { start },
                end = { end },
                position = { position },
                onStart = ::setStart,
                onEnd = ::setEnd,
            )

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Nudger("Start", start, onNudge = { setStart(start + it) })
                Nudger("End", end, onNudge = { setEnd(end + it) }, alignEnd = true)
            }

            Spacer(Modifier.height(28.dp))
            FilledTonalButton(
                onClick = {
                    if (player.isPlaying) player.pause() else {
                        player.seekTo(start)
                        player.play()
                    }
                },
                modifier = Modifier.align(Alignment.CenterHorizontally).height(56.dp),
            ) {
                Icon(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (player.isPlaying) "Stop" else "Play selection")
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = { player.pause(); pending = CutKind.REMOVE },
                enabled = !working && end - start < duration - MIN_SELECTION_MS,
                modifier = Modifier.weight(1f).height(64.dp),
            ) {
                Icon(Icons.Rounded.ContentCut, null)
                Spacer(Modifier.width(8.dp))
                Text("Cut out")
            }
            Button(
                onClick = { player.pause(); pending = CutKind.CROP },
                enabled = !working && end - start < duration,
                modifier = Modifier.weight(1f).height(64.dp),
            ) {
                Icon(Icons.Rounded.Crop, null)
                Spacer(Modifier.width(8.dp))
                Text("Keep only this")
            }
        }
    }

    pending?.let { kind ->
        val keep = when (kind) {
            CutKind.CROP -> listOf(Span(start, end))
            CutKind.REMOVE -> listOf(Span(0, start), Span(end, duration))
        }
        fun apply(asCopy: Boolean) {
            pending = null
            working = true
            scope.launch {
                player.pause()
                val result = withContext(Dispatchers.IO) { app.store.cut(id, keep, asCopy) }
                onClose(result ?: id)
            }
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(kind.title) },
            text = { Text("${kind.detail} The result is ${formatClock(keep.sumOf { it.lengthMs })} long, and the transcript is trimmed to match.") },
            confirmButton = { TextButton(onClick = { apply(asCopy = false) }) { Text("Replace original") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { pending = null }) { Text("Cancel") }
                    TextButton(onClick = { apply(asCopy = true) }) { Text("Save as copy") }
                }
            },
        )
    }

    if (working) {
        Box(Modifier.fillMaxSize().background(colors.scrim.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh) {
                LoadingIndicator(Modifier.padding(28.dp).size(56.dp))
            }
        }
    }
}

/** The whole recording with two draggable handles. Touching down grabs whichever handle is nearer. */
@Composable
private fun SelectionWaveform(
    peaks: Peaks,
    durationMs: Long,
    start: () -> Long,
    end: () -> Long,
    position: () -> Long,
    onStart: (Long) -> Unit,
    onEnd: (Long) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .height(168.dp)
            .background(colors.surfaceContainer, RoundedCornerShape(28.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .pointerInput(durationMs) {
                var draggingStart = true
                detectDragGestures(
                    onDragStart = { offset ->
                        val ms = offset.x / size.width * durationMs
                        draggingStart = abs(ms - start()) <= abs(ms - end())
                    },
                ) { change, _ ->
                    change.consume()
                    val ms = (change.position.x / size.width * durationMs).toLong()
                    if (draggingStart) onStart(ms) else onEnd(ms)
                }
            },
    ) {
        OverviewWaveform(
            peaks = peaks,
            durationMs = durationMs,
            selectionStartMs = start,
            selectionEndMs = end,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            if (durationMs <= 0) return@Canvas
            val x1 = start().toFloat() / durationMs * size.width
            val x2 = end().toFloat() / durationMs * size.width
            drawRoundRect(
                colors.primary.copy(alpha = 0.12f),
                topLeft = Offset(x1, 0f),
                size = Size(x2 - x1, size.height),
                cornerRadius = CornerRadius(8.dp.toPx()),
            )
            for (x in listOf(x1, x2)) {
                drawLine(colors.primary, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(colors.primary, radius = 9.dp.toPx(), center = Offset(x, size.height / 2f))
                drawCircle(colors.onPrimary, radius = 3.dp.toPx(), center = Offset(x, size.height / 2f))
            }
            val p = position()
            if (p in start()..end()) {
                val x = p.toFloat() / durationMs * size.width
                drawLine(colors.onSurface, Offset(x, 6.dp.toPx()), Offset(x, size.height - 6.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

/** Fine adjustment for one edge of the selection, a tenth of a second per tap. */
@Composable
private fun Nudger(label: String, valueMs: Long, onNudge: (Long) -> Unit, alignEnd: Boolean = false) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onNudge(-NUDGE_MS) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Remove, "$label earlier") }
            Text(
                formatClockFine(valueMs),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                textAlign = TextAlign.Center,
                modifier = Modifier.width(76.dp),
            )
            FilledTonalIconButton(onClick = { onNudge(NUDGE_MS) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Add, "$label later") }
        }
    }
}
