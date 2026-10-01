package app.sotto.ui.screens

import kotlinx.coroutines.delay
import app.sotto.ui.components.SheetRow
import app.sotto.ui.components.SheetGroup
import app.sotto.data.Settings
import app.sotto.audio.MicSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.produceState
import androidx.compose.material3.Surface
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sotto.SottoApp
import app.sotto.asr.SoundTagger
import app.sotto.audio.RecordingSession
import app.sotto.data.Segment
import app.sotto.data.Word
import app.sotto.ui.components.RecordButton
import app.sotto.ui.components.RecordGlyph
import app.sotto.ui.components.TapeWaveform
import app.sotto.ui.components.TranscriptView
import app.sotto.ui.components.ViewToggle
import app.sotto.ui.components.paragraphsOf
import app.sotto.ui.formatClock
import app.sotto.ui.theme.LocalSottoColors
import app.sotto.ui.theme.TimerFont
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Saving or deleting ends the session, and the screen closes itself when the session is gone.
 *
 * @param onMinimize leave the screen with the recording still running
 */
@Composable
fun RecordScreen(app: SottoApp, session: RecordingSession, onMinimize: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val paused by session.paused.collectAsState()
    val segments by session.segments.collectAsState()
    val partial by session.partial.collectAsState()
    val title by session.title.collectAsState()
    val failure by session.failure.collectAsState()
    val sourceEnded by session.sourceEnded.collectAsState()
    var showTranscript by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }

    fun save() {
        if (finishing) return
        finishing = true
        // On the app's scope, not this screen's: the screen is gone before the save has finished.
        app.scope.launch { app.recorder.stop() }
    }

    BackHandler(onBack = onMinimize)
    // A finite source (the debug WAV) running dry ends the recording the way pressing Save would.
    LaunchedEffect(sourceEnded) { if (sourceEnded) save() }

    Column(Modifier.fillMaxSize().background(colors.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMinimize) { Icon(Icons.Rounded.KeyboardArrowDown, "Keep recording in the background") }
            TitleField(title, onChange = { session.title.value = it }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }

        MicrophoneChip(app, session, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))

        AdaptivePane(content = {
        ContentCard(Modifier.weight(1f)) {
            AnimatedContent(showTranscript, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { transcript ->
                if (transcript) {
                    LiveTranscript(session.hasTranscript, segments, partial)
                } else {
                    Box(Modifier.fillMaxSize()) {
                        LiveWaveform(session, paused)
                        HearingLabel(session, paused, Modifier.align(Alignment.TopCenter).padding(top = 28.dp))
                    }
                }
            }
        }

        }, controls = {
        ViewToggle(showTranscript, { showTranscript = it }, Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp, bottom = 8.dp))
        Timer(session, paused)
        val interrupted by session.interruption.collectAsState()
        if (interrupted) {
            Text(
                "Paused for a call. Recording picks up again when it ends.",
                color = colors.primary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        failure?.let {
            Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(8.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 20.dp, bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TextButton(onClick = { confirmDelete = true }, enabled = !finishing, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
                    Text("Delete", style = MaterialTheme.typography.titleMedium)
                }
            }
            RecordButton(
                onClick = { app.recorder.setPaused(!paused) },
                size = 96.dp,
                glyph = if (paused) RecordGlyph.RECORD else RecordGlyph.PAUSE,
                description = if (paused) "Resume recording" else "Pause recording",
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                FilledTonalButton(
                    onClick = ::save,
                    enabled = !finishing,
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) {
                    Text("Save", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this recording?") },
            text = { Text("Recording will stop and nothing will be saved.") },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                    onClick = {
                        confirmDelete = false
                        finishing = true
                        app.scope.launch { app.recorder.discard() }
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep recording") } },
        )
    }
}

/** Says which microphone is being recorded from; tap to choose another, or let Android decide. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MicrophoneChip(app: SottoApp, session: RecordingSession, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var choosing by remember { mutableStateOf(false) }
    val chosen by app.settings.microphone.collectAsState()
    // Asked once a second: plugging in a headset changes the answer without the app being told.
    val name by produceState<String?>(null, session) {
        while (true) {
            value = session.inputName
            delay(1000)
        }
    }
    val current = name ?: return
    Surface(onClick = { choosing = true }, shape = CircleShape, color = colors.surfaceContainerHigh, modifier = modifier) {
        Row(Modifier.padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Mic, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(current, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        }
    }
    if (choosing) {
        val inputs = remember { MicSource.inputs(context) }
        ModalBottomSheet(onDismissRequest = { choosing = false }, containerColor = colors.surfaceContainerLow) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("Microphone", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 12.dp, bottom = 16.dp))
                SheetGroup(
                    listOf(
                        SheetRow(Icons.Rounded.AutoAwesome, "Automatic", detail = "A plugged-in headset if there is one", trailing = { RadioButton(chosen == Settings.MIC_AUTO, onClick = null) }) {
                            app.settings.setMicrophone(Settings.MIC_AUTO)
                            session.selectInput(null)
                            choosing = false
                        },
                    ) + inputs.map { device ->
                        SheetRow(Icons.Rounded.Mic, MicSource.label(device), trailing = { RadioButton(chosen == Settings.micKey(device), onClick = null) }) {
                            app.settings.setMicrophone(Settings.micKey(device))
                            session.selectInput(device)
                            choosing = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LiveTranscript(available: Boolean, segments: List<Segment>, partial: List<Word>) {
    val colors = MaterialTheme.colorScheme
    if (!available) {
        Hint("Transcription is off", "Download the speech model from Settings and your next recording will be transcribed as you speak.")
        return
    }
    if (segments.isEmpty() && partial.isEmpty()) {
        Hint("Listening", "Words appear here as you speak.")
        return
    }
    val paragraphs = remember(segments) { paragraphsOf(segments) }
    val listState = rememberLazyListState()
    // Follow the newest words, unless the reader has scrolled back up to look at something.
    val wordCount = segments.sumOf { it.words.size } + partial.size
    LaunchedEffect(wordCount) {
        val last = listState.layoutInfo.totalItemsCount - 1
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (last >= 0 && lastVisible >= last - 1) listState.scrollToItem(last, Int.MAX_VALUE / 2)
    }
    TranscriptView(paragraphs, partial = partial, listState = listState, modifier = Modifier.fillMaxSize())
}

@Composable
private fun Hint(title: String, body: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Collects the fast-changing bar count itself, so only the waveform recomposes as audio arrives. */
@Composable
private fun LiveWaveform(session: RecordingSession, paused: Boolean) {
    val peakCount by session.peakCount.collectAsState()
    val sounds by session.soundSpans.collectAsState()
    TapeWaveform(
        sounds = sounds,
        peaks = session.peaks,
        peakCount = peakCount,
        positionMs = { session.elapsedMs.value },
        barColor = if (paused) MaterialTheme.colorScheme.outline else LocalSottoColors.current.speech,
        playheadColor = LocalSottoColors.current.record,
        modifier = Modifier.fillMaxSize(),
    )
}

/** Names what is being heard this second, above the waveform and in the color the waveform is drawn in for it. */
@Composable
private fun HearingLabel(session: RecordingSession, paused: Boolean, modifier: Modifier = Modifier) {
    val hearing by session.hearing.collectAsState()
    val palette = LocalSottoColors.current
    val heard = hearing?.takeIf { !paused } ?: return
    val tint = if (heard == SoundTagger.SPEECH) palette.speech else palette.sound(heard)
    Surface(shape = CircleShape, color = tint.copy(alpha = 0.16f), contentColor = tint, modifier = modifier) {
        Text(heard, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
    }
}

@Composable
private fun Timer(session: RecordingSession, paused: Boolean) {
    val seconds by remember(session) { session.elapsedMs.map { it / 1000 }.distinctUntilChanged() }.collectAsState(0L)
    val blink = rememberInfiniteTransition(label = "blink")
    val pulse by blink.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha")
    Text(
        formatClock(seconds * 1000),
        style = MaterialTheme.typography.displayLarge.copy(
            fontFamily = TimerFont,
            fontSize = 64.sp,
            lineHeight = 72.sp,
            // Fixed-width digits, so the clock does not shuffle sideways as it counts.
            fontFeatureSettings = "tnum",
        ),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().alpha(if (paused) pulse else 1f),
    )
}

@Composable
private fun TitleField(title: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = title,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onSurface, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = modifier,
    )
}
