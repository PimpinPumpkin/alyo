package app.sotto.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sotto.SottoApp
import app.sotto.data.Segment
import app.sotto.data.withText
import app.sotto.playback.Player
import app.sotto.ui.components.Paragraph
import app.sotto.ui.components.SeekBar
import app.sotto.ui.components.SheetRow
import app.sotto.ui.components.TapeWaveform
import app.sotto.ui.components.TranscriptView
import app.sotto.ui.components.ViewToggle
import app.sotto.ui.components.paragraphsOf
import app.sotto.ui.formatStamp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)

private fun nextSpeed(current: Float): Float = SPEEDS[(SPEEDS.indexOf(current) + 1).mod(SPEEDS.size)]

/**
 * Word times mark where the model first recognised a word, which is a touch after its onset, so
 * jumping to exactly that time clips the first sound. Jumps land this much early, and the
 * highlight runs the same amount ahead so the tapped word is still the one lit.
 */
private const val LEAD_IN_MS = 180L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerScreen(
    app: SottoApp,
    id: String,
    autoplay: Boolean,
    onBack: () -> Unit,
    onCrop: () -> Unit,
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
    // Follow playback frame by frame, but only while something is moving: a paused player
    // should not keep the screen redrawing.
    LaunchedEffect(player, player.isPlaying, player.seekCount, player.durationMs) {
        position = player.positionMs
        while (player.isPlaying && isActive) {
            withFrameNanos { }
            position = player.positionMs
        }
    }

    var showTranscript by rememberSaveable { mutableStateOf(!transcript.isEmpty) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingSegment by remember { mutableStateOf<Segment?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val busy = meta.pendingRefine > 0
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize().background(colors.surface)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text(
                    meta.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { renaming = true }.padding(horizontal = 8.dp, vertical = 8.dp),
                )
                IconButton(onClick = { app.store.setStarred(id, !meta.starred) }) {
                    Icon(
                        if (meta.starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        if (meta.starred) "Remove from favourites" else "Add to favourites",
                        tint = if (meta.starred) colors.primary else colors.onSurface,
                    )
                }
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            }

            AnimatedVisibility(busy) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Polishing the transcript", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }
            }

            ContentCard(Modifier.weight(1f)) {
                AnimatedContent(showTranscript, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { showingTranscript ->
                    if (!showingTranscript) {
                        TapeWaveform(
                            peaks = peaks,
                            peakCount = peaks.size,
                            positionMs = { position },
                            onScrub = { delta -> player.seekBy(delta) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (paragraphs.isEmpty()) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
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
                        Column(Modifier.fillMaxSize()) {
                            if (searching) {
                                TranscriptSearch(
                                    query = query,
                                    onQuery = { query = it },
                                    paragraphs = paragraphs,
                                    listState = listState,
                                    onJump = { ms -> player.seekTo(ms - LEAD_IN_MS) },
                                    onClose = { searching = false; query = "" },
                                )
                            }
                            val activeParagraph by remember(paragraphs) {
                                derivedStateOf { paragraphs.indexOfFirst { position + LEAD_IN_MS >= it.startMs && position + LEAD_IN_MS < it.endMs } }
                            }
                            // Keep the spoken paragraph in view while playing, without fighting a reader who is scrolling.
                            LaunchedEffect(activeParagraph, player.isPlaying) {
                                if (activeParagraph >= 0 && player.isPlaying && !listState.isScrollInProgress) {
                                    listState.animateScrollToItem(activeParagraph, scrollOffset = -80)
                                }
                            }
                            TranscriptView(
                                paragraphs = paragraphs,
                                positionMs = { position + LEAD_IN_MS },
                                editing = editing,
                                query = if (searching) query else "",
                                listState = listState,
                                onWordTap = { word -> player.seekTo(word.startMs - LEAD_IN_MS) },
                                onEditSegment = { segment -> player.pause(); editingSegment = segment },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                            if (editing) EditBar(onDone = { editing = false })
                        }
                    }
                }
            }

            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                // Speed lives at the edge of the same row, one tap away rather than in the menu.
                Surface(
                    onClick = { player.changeSpeed(nextSpeed(player.speed)) },
                    shape = CircleShape,
                    color = if (player.speed == 1f) colors.surfaceContainerHighest else colors.primary,
                    contentColor = if (player.speed == 1f) colors.onSurface else colors.onPrimary,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp).size(width = 72.dp, height = 56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(speedLabel(player.speed), style = MaterialTheme.typography.titleMedium)
                    }
                }
                ViewToggle(showTranscript, { showTranscript = it }, Modifier.align(Alignment.Center))
            }
            SeekBar(peaks, meta.durationMs, positionMs = { position }, onSeek = player::seekTo, modifier = Modifier.padding(horizontal = 12.dp))
            TimeLabels({ position }, meta.durationMs)
            TransportRow(player)
        }

        if (renaming) {
            EditTitleOverlay(meta.title, onDismiss = { renaming = false }) { app.store.rename(id, it); renaming = false }
        }
    }

    if (menuOpen) {
        RecordingMenuSheet(
            meta = meta,
            onDismiss = { menuOpen = false },
            onEditTitle = { menuOpen = false; renaming = true },
            onShare = { menuOpen = false; sharing = true },
            onCrop = if (busy) null else ({ menuOpen = false; player.pause(); onCrop() }),
            onDelete = { menuOpen = false; deleting = true },
            extra = listOf(
                SheetRow(Icons.Rounded.Search, "Search transcript", enabled = !transcript.isEmpty) {
                    menuOpen = false
                    showTranscript = true
                    searching = true
                },
                SheetRow(Icons.Rounded.EditNote, "Edit transcript", enabled = !busy && !transcript.isEmpty) {
                    menuOpen = false
                    showTranscript = true
                    editing = true
                },
                SheetRow(
                    Icons.Rounded.FastForward,
                    "Playback speed",
                    trailing = {
                        Surface(shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary) {
                            Text(speedLabel(player.speed), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    },
                ) { player.changeSpeed(nextSpeed(player.speed)) },
            ),
        )
    }
    editingSegment?.let { segment ->
        SegmentEditor(
            segment = segment,
            onDismiss = { editingSegment = null },
            onListen = { player.seekTo(segment.startMs - LEAD_IN_MS); player.play() },
            onSave = { text ->
                player.pause()
                app.store.updateTranscript(id) { current ->
                    current.copy(segments = current.segments.mapNotNull { if (it.id == segment.id) it.withText(text) else it })
                }
                editingSegment = null
            },
        )
    }
    if (deleting) DeleteDialog(meta.title, onDismiss = { deleting = false }) { deleting = false; player.pause(); app.store.delete(id); onBack() }
    if (sharing) ShareSheet(app, meta, onDismiss = { sharing = false })
}

/** Find-in-transcript: a field, a "2 of 5" count, and arrows that jump playback to each match. */
@Composable
private fun TranscriptSearch(
    query: String,
    onQuery: (String) -> Unit,
    paragraphs: List<Paragraph>,
    listState: LazyListState,
    onJump: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Each match as (paragraph index, start time of the word it begins in).
    val matches = remember(paragraphs, query) {
        paragraphs.flatMapIndexed { index, paragraph ->
            paragraph.find(query.trim()).map { index to paragraph.words[paragraph.wordAtChar(it.first)].startMs.toLong() }
        }
    }
    var current by remember(matches) { mutableIntStateOf(0) }
    fun go(to: Int) {
        if (matches.isEmpty()) return
        current = to.mod(matches.size)
        val (paragraph, ms) = matches[current]
        onJump(ms)
        scope.launch { listState.animateScrollToItem(paragraph, scrollOffset = -80) }
    }

    Surface(shape = CircleShape, color = colors.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
        Row(Modifier.padding(start = 18.dp, end = 4.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Search transcript", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { go(current) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotBlank()) {
                Text(
                    if (matches.isEmpty()) "0" else "${current + 1} of ${matches.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                IconButton(onClick = { go(current - 1) }, enabled = matches.isNotEmpty()) { Icon(Icons.Rounded.KeyboardArrowUp, "Previous match") }
                IconButton(onClick = { go(current + 1) }, enabled = matches.isNotEmpty()) { Icon(Icons.Rounded.KeyboardArrowDown, "Next match") }
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close search") }
        }
    }
}

@Composable
private fun EditBar(onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerHighest, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Edit, null, tint = colors.primary)
            Spacer(Modifier.width(14.dp))
            Text("Tap a sentence to correct it", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).imePadding().animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Correct this sentence", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(formatStamp(segment.startMs.toLong()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
