package app.sotto.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sotto.SottoApp
import app.sotto.audio.Peaks
import app.sotto.data.Segment
import app.sotto.data.withText
import app.sotto.playback.Player
import app.sotto.ui.components.OverviewWaveform
import app.sotto.ui.components.TapeWaveform
import app.sotto.ui.components.TranscriptView
import app.sotto.ui.components.ViewToggle
import app.sotto.ui.components.paragraphsOf
import app.sotto.ui.dayLabel
import app.sotto.ui.formatClock
import app.sotto.ui.theme.TimerFont
import app.sotto.ui.timeOfDay
import kotlinx.coroutines.isActive

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerScreen(
    app: SottoApp,
    id: String,
    autoplay: Boolean,
    onBack: () -> Unit,
    onTrim: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val recordings by app.store.recordings.collectAsState()
    val meta = recordings.firstOrNull { it.id == id }
    if (meta == null) {
        // Deleted from under us.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val transcript by app.store.transcript(id).collectAsState()
    val paragraphs = remember(transcript) { paragraphsOf(transcript.segments) }
    // Reloaded whenever the audio is cut, which is exactly when the duration changes.
    val peaks = remember(id, meta.durationMs) { app.store.peaks(id) }

    val player = remember(id) { Player(context.applicationContext) }
    DisposableEffect(player) { onDispose { player.release() } }
    var autoplayed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(player, meta.durationMs) {
        player.load(app.store.audio(id))
        if (autoplay && !autoplayed) {
            autoplayed = true
            player.play()
        }
    }

    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (isActive) {
            withFrameNanos { }
            position = player.positionMs
        }
    }

    var showTranscript by rememberSaveable { mutableStateOf(!transcript.isEmpty) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingSegment by remember { mutableStateOf<Segment?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val busy = meta.pendingRefine > 0

    Column(Modifier.fillMaxSize().background(colors.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { renaming = true }.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(meta.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    dayLabel(meta.createdAt) + ", " + timeOfDay(meta.createdAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            IconButton(onClick = { exporting = true }) { Icon(Icons.Rounded.IosShare, "Share or export") }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit transcript") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        enabled = !busy && !transcript.isEmpty,
                        onClick = { menuOpen = false; editing = true; showTranscript = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Crop or cut audio") },
                        leadingIcon = { Icon(Icons.Rounded.ContentCut, null) },
                        enabled = !busy,
                        onClick = { menuOpen = false; player.pause(); onTrim() },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
                        onClick = { menuOpen = false; renaming = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = { menuOpen = false; deleting = true },
                    )
                }
            }
        }

        ViewToggle(showTranscript, { showTranscript = it }, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))

        AnimatedVisibility(busy) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Polishing the transcript", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(showTranscript, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { showingTranscript ->
                if (!showingTranscript) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                        TapeWaveform(
                            peaks = peaks,
                            peakCount = peaks.size,
                            positionMs = { position },
                            onScrub = { delta -> player.seekBy(delta) },
                            modifier = Modifier.fillMaxWidth().height(240.dp),
                        )
                        Spacer(Modifier.height(24.dp))
                        val seconds by remember { derivedStateOf { position / 1000 } }
                        Text(
                            formatClock(seconds * 1000),
                            style = MaterialTheme.typography.displayLarge.copy(fontFamily = TimerFont, fontSize = 64.sp, fontFeatureSettings = "tnum"),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else if (paragraphs.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No transcript", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Nothing was transcribed for this recording.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    val listState = rememberLazyListState()
                    val activeParagraph by remember(paragraphs) {
                        derivedStateOf { paragraphs.indexOfFirst { position >= it.startMs && position < it.endMs } }
                    }
                    // Keep the spoken paragraph in view while playing, without fighting a reader who is scrolling.
                    LaunchedEffect(activeParagraph, player.isPlaying) {
                        if (activeParagraph >= 0 && player.isPlaying && !listState.isScrollInProgress) {
                            listState.animateScrollToItem(activeParagraph, scrollOffset = -120)
                        }
                    }
                    TranscriptView(
                        paragraphs = paragraphs,
                        positionMs = { position },
                        editing = editing,
                        listState = listState,
                        onWordTap = { word -> player.seekTo(word.startMs.toLong()) },
                        onEditSegment = { segment -> player.pause(); editingSegment = segment },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (editing) {
            EditBar(onDone = { editing = false })
        } else {
            Transport(player, peaks, meta.durationMs, positionMs = { position })
        }
    }

    editingSegment?.let { segment ->
        SegmentEditor(
            segment = segment,
            onDismiss = { editingSegment = null },
            onListen = { player.seekTo(segment.startMs.toLong()); player.play() },
            onSave = { text ->
                player.pause()
                app.store.updateTranscript(id) { current ->
                    current.copy(segments = current.segments.mapNotNull { if (it.id == segment.id) it.withText(text) else it })
                }
                editingSegment = null
            },
        )
    }
    if (renaming) RenameDialog(meta.title, onDismiss = { renaming = false }) { app.store.rename(id, it); renaming = false }
    if (deleting) DeleteDialog(meta.title, onDismiss = { deleting = false }) { deleting = false; player.pause(); app.store.delete(id); onBack() }
    if (exporting) ExportSheet(app, meta, onDismiss = { exporting = false })
}

/** Scrub strip, clock, and the play / skip / speed row. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Transport(player: Player, peaks: Peaks, durationMs: Long, positionMs: () -> Long) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 20.dp)) {
        OverviewWaveform(
            peaks = peaks,
            durationMs = durationMs,
            positionMs = positionMs,
            selectionEndMs = positionMs,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { player.seekTo((it.x / size.width * durationMs).toLong()) }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        player.seekTo((change.position.x / size.width * durationMs).toLong())
                    }
                },
        )
        // Whole seconds only, so the row recomposes once a second rather than once a frame.
        val seconds by remember { derivedStateOf { positionMs() / 1000 } }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatClock(seconds * 1000), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = colors.onSurfaceVariant)
            Text(formatClock(durationMs), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                TextButton(onClick = { player.changeSpeed(SPEEDS[(SPEEDS.indexOf(player.speed) + 1).mod(SPEEDS.size)]) }) {
                    Text(speedLabel(player.speed), style = MaterialTheme.typography.titleMedium)
                }
            }
            IconButton(onClick = { player.seekBy(-5_000) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.Replay5, "Back 5 seconds", Modifier.size(32.dp))
            }
            Spacer(Modifier.width(12.dp))
            PlayButton(player.isPlaying, onClick = player::toggle)
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = { player.seekBy(10_000) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.Forward10, "Forward 10 seconds", Modifier.size(32.dp))
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Round while paused, a soft square while playing: the shape itself says which state it is in. */
@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (playing) 28.dp else 48.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "corner")
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = colors.primary,
        contentColor = colors.onPrimary,
        modifier = Modifier.size(width = 112.dp, height = 88.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(44.dp))
        }
    }
}

@Composable
private fun EditBar(onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Edit, null, tint = colors.primary)
            Spacer(Modifier.width(14.dp))
            Text("Tap any sentence to correct it", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Button(onClick = onDone) {
                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Done")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentEditor(segment: Segment, onDismiss: () -> Unit, onListen: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(segment.id) { mutableStateOf(segment.text) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).imePadding().animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Correct this sentence", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(formatClock(segment.startMs.toLong()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Words you leave alone stay locked to the audio. Clear the box to remove the sentence.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onListen) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Listen")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(text) }) { Text("Save") }
            }
        }
    }
}

private fun speedLabel(speed: Float): String = (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"
