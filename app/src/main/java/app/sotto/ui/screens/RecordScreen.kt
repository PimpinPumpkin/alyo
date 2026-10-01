package app.sotto.ui.screens

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
import kotlinx.coroutines.launch

/**
 * Saving or deleting ends the session, and the screen closes itself when the session is gone.
 *
 * @param onMinimise leave the screen with the recording still running
 */
@Composable
fun RecordScreen(app: SottoApp, session: RecordingSession, onMinimise: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val red = LocalSottoColors.current.record
    val elapsed by session.elapsedMs.collectAsState()
    val paused by session.paused.collectAsState()
    val peakCount by session.peakCount.collectAsState()
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

    BackHandler(onBack = onMinimise)
    // A finite source (the debug WAV) running dry ends the recording the way pressing Save would.
    LaunchedEffect(sourceEnded) { if (sourceEnded) save() }

    Column(Modifier.fillMaxSize().background(colors.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMinimise) { Icon(Icons.Rounded.KeyboardArrowDown, "Keep recording in the background") }
            TitleField(title, onChange = { session.title.value = it }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }

        ViewToggle(showTranscript, { showTranscript = it }, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(showTranscript, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { transcript ->
                if (transcript) {
                    LiveTranscript(session.hasTranscript, segments, partial)
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                        TapeWaveform(
                            peaks = session.peaks,
                            peakCount = peakCount,
                            positionMs = { elapsed },
                            playedColor = if (paused) colors.outline else colors.primary,
                            playheadColor = red,
                            modifier = Modifier.fillMaxWidth().height(240.dp),
                        )
                    }
                }
            }
        }

        Timer(elapsed, paused)
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
    TranscriptView(paragraphs, partial = partial, listState = listState, modifier = Modifier.fillMaxSize().background(colors.surface))
}

@Composable
private fun Hint(title: String, body: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Timer(elapsedMs: Long, paused: Boolean) {
    val blink = rememberInfiniteTransition(label = "blink")
    val pulse by blink.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha")
    Text(
        formatClock(elapsedMs),
        style = MaterialTheme.typography.displayLarge.copy(
            fontFamily = TimerFont,
            fontSize = 72.sp,
            lineHeight = 80.sp,
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
