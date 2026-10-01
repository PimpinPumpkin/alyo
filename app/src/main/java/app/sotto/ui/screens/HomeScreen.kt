package app.sotto.ui.screens

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.rounded.History
import app.sotto.service.LibraryJobs
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.rounded.Add
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sotto.SottoApp
import app.sotto.asr.InstallState
import app.sotto.asr.works
import app.sotto.audio.Peaks
import app.sotto.audio.RecordingSession
import app.sotto.data.RecordingMeta
import app.sotto.data.SoundSpan
import app.sotto.ui.components.RecordButton
import app.sotto.ui.components.RecordingDot
import app.sotto.ui.components.SpeechBar
import app.sotto.ui.formatStamp
import app.sotto.ui.monthLabel
import app.sotto.ui.shortDate
import app.sotto.ui.formatClock
import app.sotto.ui.theme.LocalSottoColors
import app.sotto.ui.timeOfDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    app: SottoApp,
    onOpen: (id: String, autoplay: Boolean, find: String) -> Unit,
    onRecord: () -> Unit,
    onReturnToRecording: () -> Unit,
    onSettings: () -> Unit,
    onShare: (RecordingMeta) -> Unit,
    onCrop: (RecordingMeta) -> Unit,
) {
    val all by app.store.recordings.collectAsState()
    val context = LocalContext.current
    val allJobs by app.jobs.active.collectAsState()
    // A recording being transcribed again already has a card of its own, which shows the progress.
    val jobs = allJobs.filter { it.recordingId == null }
    val jobError by app.jobs.errors.collectAsState()
    LaunchedEffect(jobError) {
        jobError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            app.jobs.clearError()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach(app.jobs::import) }
    val session by app.recorder.session.collectAsState()
    val modelStates by app.models.states.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    val history by app.settings.searches.collectAsState()
    var menuFor by remember { mutableStateOf<RecordingMeta?>(null) }
    var renaming by remember { mutableStateOf<RecordingMeta?>(null) }
    var deleting by remember { mutableStateOf<RecordingMeta?>(null) }

    // The one being recorded right now is not in the library yet.
    // Searching reads every transcript, so it runs off the main thread.
    val shown by produceState(all, all, query) {
        value = if (query.isBlank()) all else withContext(Dispatchers.IO) { app.store.search(query) }
    }
    val groups = remember(shown) { shown.groupBy { monthLabel(it.createdAt) }.toList() }
    val qualityId by app.settings.quality.collectAsState()
    val languageCode by app.settings.language.collectAsState()
    val setup = remember(modelStates, qualityId, languageCode) {
        val pack = app.settings.pack
        val needed = app.settings.neededModels
        val downloading = needed.mapNotNull { modelStates[it.id] as? InstallState.Downloading }
        when {
            downloading.isNotEmpty() -> {
                val total = needed.sumOf { it.source.downloadBytes }
                val done = needed.sumOf { spec ->
                    when (val state = modelStates[spec.id]) {
                        is InstallState.Downloading -> state.doneBytes
                        InstallState.Installed -> spec.source.downloadBytes
                        else -> 0L
                    }
                }
                SetupState.Downloading(done.toFloat() / total)
            }
            !modelStates[pack.live.id].works -> SetupState.Off
            // Any installed second-pass model will do; the chosen one is only a preference.
            app.settings.chosenQuality.refiner != null && pack.qualities.none { modelStates[it.refiner?.id].works } -> SetupState.RoughOnly
            needed.any { modelStates[it.id] == InstallState.Outdated } -> SetupState.Update
            else -> SetupState.Ready
        }
    }
    val colors = MaterialTheme.colorScheme

    // Focusable so that when a dialog closes, focus comes back here and not to the search field,
    // which would pop the keyboard up over the list.
    Box(Modifier.fillMaxSize().background(colors.surface).focusable()) {
        val gridState = rememberLazyGridState()
        // A new import appears at the top; bring it into view rather than leave it scrolled off.
        LaunchedEffect(jobs.size) { if (jobs.isNotEmpty()) gridState.animateScrollToItem(0) }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(340.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 160.dp,
            ),
        ) {
            if (setup != SetupState.Ready && query.isBlank()) {
                item(key = "setup", span = { GridItemSpan(maxLineSpan) }) { SetupCard(setup, onSettings) }
            }
            items(jobs, key = { it.key }, span = { GridItemSpan(maxLineSpan) }) { job -> JobCard(job) }
            for ((label, items) in groups) {
                item(key = "h-$label", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        label,
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.padding(start = 8.dp, top = 28.dp, bottom = 16.dp),
                    )
                }
                items(items, key = { it.id }) { meta ->
                    RecordingCard(
                        app = app,
                        meta = meta,
                        // Opening a result is what makes a search worth remembering.
                        onClick = { app.settings.rememberSearch(query); onOpen(meta.id, false, query.trim()) },
                        onPlay = { app.settings.rememberSearch(query); onOpen(meta.id, true, query.trim()) },
                        onLongClick = { menuFor = meta },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        if (shown.isEmpty()) {
            EmptyState(searching = query.isNotBlank(), modifier = Modifier.align(Alignment.Center))
        }

        // Search bar floats over the list, with a scrim so rows fade out underneath it.
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(0.75f to colors.surface, 1f to Color.Transparent))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Column {
                SearchPill(query, onQuery = { query = it }, onSettings = onSettings, onImport = { importer.launch(arrayOf("audio/*", "video/*")) }, onFocus = { searchFocused = it })
                // Recent searches, offered while the field is in use and still empty.
                if (searchFocused && query.isEmpty() && history.isNotEmpty()) {
                    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            for (past in history) {
                                Row(Modifier.fillMaxWidth().clickable { query = past }.padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.History, null, tint = colors.onSurfaceVariant)
                                    Spacer(Modifier.width(16.dp))
                                    Text(past, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { app.settings.forgetSearch(past) }) { Icon(Icons.Rounded.Close, "Remove $past from search history", tint = colors.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Centered on a phone held upright; tucked into the corner when the screen is wide, where
        // the middle of the bottom edge is the middle of the list.
        val wide = isWide()
        Box(
            Modifier
                .align(if (wide) Alignment.BottomEnd else Alignment.BottomCenter)
                .then(if (wide) Modifier else Modifier.fillMaxWidth())
                .navigationBarsPadding()
                .padding(bottom = if (wide) 20.dp else 28.dp, end = if (wide) 28.dp else 0.dp),
            contentAlignment = Alignment.Center,
        ) {
            val active = session
            if (active == null) {
                RecordButton(onClick = onRecord)
            } else {
                RecordingPill(active, onClick = onReturnToRecording)
            }
        }

        renaming?.let { meta ->
            EditTitleOverlay(meta.title, onDismiss = { renaming = null }) { app.store.rename(meta.id, it); renaming = null }
        }
    }

    menuFor?.let { meta ->
        RecordingMenuSheet(
            meta = meta,
            onDismiss = { menuFor = null },
            onEditTitle = { menuFor = null; renaming = meta },
            onShare = { menuFor = null; onShare(meta) },
            onCrop = if (meta.pendingRefine > 0) null else ({ menuFor = null; onCrop(meta) }),
            onDelete = { menuFor = null; deleting = meta },
        )
    }
    deleting?.let { meta ->
        DeleteDialog(meta.title, onDismiss = { deleting = null }) { app.store.delete(meta.id); deleting = null }
    }
}

@Composable
private fun SearchPill(query: String, onQuery: (String) -> Unit, onSettings: () -> Unit, onImport: () -> Unit, onFocus: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    // No container: on the original the search row is just an icon and text on the page.
    Box(Modifier.fillMaxWidth().height(56.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 4.dp)) {
            Icon(Icons.Rounded.Search, null, tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text("Search your recordings", style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { onFocus(it.isFocused) },
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery(""); focus.clearFocus() }) { Icon(Icons.Rounded.Close, "Clear search") }
            } else {
                IconButton(onClick = onImport) { Icon(Icons.Rounded.Add, "Import audio", tint = colors.onSurfaceVariant) }
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = colors.onSurfaceVariant) }
            }
        }
    }
}

/** One recording: its title and a play button, the date and length, and a line showing where the speech is. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RecordingCard(
    app: SottoApp,
    meta: RecordingMeta,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // Reloaded when the length changes, which is when the audio has been cut.
    val peaks by produceState<Peaks?>(null, meta.id, meta.durationMs) {
        value = withContext(Dispatchers.IO) { app.store.peaks(meta.id) }
    }
    val activeJobs by app.jobs.active.collectAsState()
    val job = activeJobs.firstOrNull { it.recordingId == meta.id }
    val labeling by app.store.labeling.collectAsState()
    val sounds by produceState(emptyList<SoundSpan>(), meta.id, meta.durationMs, meta.pendingRefine) {
        value = withContext(Dispatchers.IO) { app.store.sounds(meta.id) }
    }
    Surface(shape = RoundedCornerShape(36.dp), color = colors.surfaceContainer, modifier = modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(meta.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (meta.starred) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Rounded.Star, "Favorite", tint = colors.primary, modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayArrow, "Play ${meta.title}", Modifier.size(32.dp)) }
            }
            Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(shortDate(meta.createdAt), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                if (meta.pendingRefine > 0 || meta.id in labeling) {
                    Spacer(Modifier.width(12.dp))
                    LoadingIndicator(Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (job?.progress != null) "Transcribing ${(job.progress * 100).toInt()}%" else if (meta.pendingRefine > 0) "Polishing transcript" else "Labeling speakers", style = MaterialTheme.typography.bodySmall, color = colors.primary)
                }
                Spacer(Modifier.weight(1f))
                Text(formatStamp(meta.durationMs), style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"), color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))
            SpeechBar(peaks, Modifier.padding(end = 12.dp), sounds = sounds, durationMs = meta.durationMs)
        }
    }
}

/** A file on its way into the library. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun JobCard(job: LibraryJobs.Job) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(36.dp), color = colors.secondaryContainer, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text(job.title, style = MaterialTheme.typography.titleLarge, color = colors.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (job.progress != null) "Importing and transcribing  ·  ${(job.progress * 100).toInt()}%" else "Finishing",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSecondaryContainer,
            )
            Spacer(Modifier.height(14.dp))
            val progress = job.progress
            if (progress != null) LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RecordingPill(session: RecordingSession, onClick: () -> Unit) {
    val elapsed by session.elapsedMs.collectAsState()
    val paused by session.paused.collectAsState()
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = CircleShape, color = colors.surfaceContainerLowest, shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 28.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            RecordingDot(color = if (paused) colors.outline else LocalSottoColors.current.record)
            Spacer(Modifier.width(12.dp))
            Text(
                (if (paused) "Paused  " else "Recording  ") + formatClock(elapsed),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyState(searching: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.padding(horizontal = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(132.dp).background(colors.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (searching) Icons.Rounded.Search else Icons.Rounded.Mic,
                null,
                tint = colors.onPrimaryContainer,
                modifier = Modifier.size(56.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            if (searching) "Nothing matches" else "No recordings yet",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (searching) "Search looks through titles and every word of your transcripts." else "Tap the red button to start. Everything stays on this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private sealed interface SetupState {
    /** No speech model: recordings are audio only. */
    data object Off : SetupState

    /** Live model only: text appears, but without the accurate second pass. */
    data object RoughOnly : SetupState
    data class Downloading(val fraction: Float) : SetupState

    /** Everything works, on a model this version of the app has a newer one for. */
    data object Update : SetupState
    data object Ready : SetupState
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SetupCard(state: SetupState, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (title, body) = when (state) {
        SetupState.Off -> "Turn on transcription" to "Download the speech model once and recordings are transcribed as you speak, offline."
        SetupState.RoughOnly -> "Finish setting up transcription" to "The accurate model is not downloaded yet, so transcripts come out rough and unpunctuated."
        is SetupState.Downloading -> "Downloading speech model" to "${(state.fraction * 100).toInt()}% done. You can keep using the app."
        SetupState.Update -> "A newer speech model is available" to "Transcription keeps working with the one you have. Tap to update when you are on Wi-Fi."
        SetupState.Ready -> "" to ""
    }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(24.dp),
        color = colors.tertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Translate, null, tint = colors.onTertiaryContainer)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onTertiaryContainer)
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onTertiaryContainer)
                }
            }
            if (state is SetupState.Downloading) {
                Spacer(Modifier.height(14.dp))
                LinearWavyProgressIndicator(
                    progress = { state.fraction },
                    color = colors.onTertiaryContainer,
                    trackColor = colors.onTertiaryContainer.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
