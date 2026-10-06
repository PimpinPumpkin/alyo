package app.alyo.ime

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class PanelPhase {
    /** The microphone is open and recording, but the speech model is still loading, so no words can appear yet. */
    WARMING,
    LISTENING,
    FINISHING,
}

/**
 * What takes the keyboard's place while dictating. The words themselves appear in the text field,
 * so the panel only has to say that it is listening and offer undo, backspace and Done.
 *
 * @param tall keep the height of a keyboard, so the app behind does not move when switching to
 *   one to correct something; otherwise a single slim row that leaves the app in view
 * @param problem why dictation cannot start (no model, no permission), or null
 * @param level how loud the microphone is, 0 to 1, read on every frame
 */
@Composable
fun DictationPanel(
    tall: Boolean,
    phase: PanelPhase,
    problem: String?,
    canUndo: Boolean,
    level: () -> Float,
    onDone: () -> Unit,
    onUndo: () -> Unit,
    onBackspace: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    if (problem == null) KeepScreenOn()
    Surface(color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                problem != null -> Column(
                    Modifier.padding(horizontal = 24.dp).padding(top = 14.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { Problem(problem, onOpenApp, onDone) }
                tall -> TallPanel(phase, canUndo, level, onDone, onUndo, onBackspace)
                else -> SlimPanel(phase, canUndo, level, onDone, onUndo, onBackspace)
            }
        }
    }
}

/** One row: a microphone that swells with the voice, the status, then undo, backspace and Done. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SlimPanel(phase: PanelPhase, canUndo: Boolean, level: () -> Float, onDone: () -> Unit, onUndo: () -> Unit, onBackspace: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shown = followLevel(level)
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (phase == PanelPhase.FINISHING) {
                LoadingIndicator(Modifier.size(40.dp))
            } else {
                Box(
                    Modifier
                        .size(48.dp)
                        .graphicsLayer {
                            // Hidden behind the microphone in silence, out to its full size at the loudest.
                            val scale = 0.75f + shown.floatValue * 0.25f
                            scaleX = scale
                            scaleY = scale
                        }
                        .background(colors.primaryContainer, CircleShape),
                )
                Box(Modifier.size(36.dp).background(colors.primary, CircleShape))
                Icon(Icons.Rounded.Mic, null, tint = colors.onPrimary, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Status(phase, Modifier.weight(1f))
        FilledTonalIconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.Undo, "Undo the last sentence")
        }
        Spacer(Modifier.width(8.dp))
        RepeatingKey(Icons.AutoMirrored.Rounded.Backspace, "Backspace", onBackspace, size = 48.dp)
        Spacer(Modifier.width(8.dp))
        Button(onClick = onDone, enabled = phase != PanelPhase.FINISHING, modifier = Modifier.height(48.dp)) {
            Text("Done", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** As tall as a keyboard: a small level meter and the status in the middle, the buttons along the bottom. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TallPanel(phase: PanelPhase, canUndo: Boolean, level: () -> Float, onDone: () -> Unit, onUndo: () -> Unit, onBackspace: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(TALL_HEIGHT).padding(horizontal = 24.dp).padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                if (phase == PanelPhase.FINISHING) LoadingIndicator(Modifier.size(40.dp)) else LevelBars(level)
            }
            Spacer(Modifier.height(12.dp))
            Status(phase)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Rounded.Undo, "Undo the last sentence")
            }
            Spacer(Modifier.width(16.dp))
            FilledTonalButton(onClick = onDone, enabled = phase != PanelPhase.FINISHING, modifier = Modifier.weight(1f).height(52.dp)) {
                Text("Done", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(16.dp))
            RepeatingKey(Icons.AutoMirrored.Rounded.Backspace, "Backspace", onBackspace, size = 52.dp)
        }
    }
}

/** About what a keyboard takes up, so the app behind sits where it would with one. */
private val TALL_HEIGHT = 264.dp

@Composable
private fun Status(phase: PanelPhase, modifier: Modifier = Modifier) {
    AnimatedContent(phase, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status", modifier = modifier) { now ->
        Text(
            when (now) {
                // The microphone is open while the model loads and nothing said then is lost; the
                // words follow as soon as it is up. So this is listening, and says so.
                PanelPhase.WARMING -> "Listening"
                PanelPhase.LISTENING -> "Listening"
                PanelPhase.FINISHING -> "Finishing"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The microphone level, followed by hand once a frame: quick to rise with a word and slower to fall, like a meter. */
@Composable
private fun followLevel(level: () -> Float): MutableFloatState {
    val currentLevel by rememberUpdatedState(level)
    val shown = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            val target = currentLevel()
            val now = shown.floatValue
            shown.floatValue = now + (target - now) * (if (target > now) 0.45f else 0.08f)
        }
    }
    return shown
}

/** Five bars that rise with the voice, tallest in the middle. */
@Composable
private fun LevelBars(level: () -> Float) {
    val color = MaterialTheme.colorScheme.primary
    val shown = followLevel(level)
    Canvas(Modifier.size(width = 44.dp, height = 40.dp)) {
        val bar = 4.dp.toPx()
        val gap = (size.width - bar * BAR_REACH.size) / (BAR_REACH.size - 1)
        BAR_REACH.forEachIndexed { i, reach ->
            val tall = bar + (size.height - bar) * (shown.floatValue * reach).coerceIn(0f, 1f)
            drawRoundRect(
                color,
                topLeft = Offset(i * (bar + gap), (size.height - tall) / 2),
                size = Size(bar, tall),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

private val BAR_REACH = floatArrayOf(0.5f, 0.8f, 1f, 0.8f, 0.5f)

/**
 * Keeps the screen from going dark for as long as this is shown. Someone dictating does not
 * touch the phone, and the phone takes that for nobody being there.
 */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * The shape in the middle. While listening it turns slowly and swells with the voice. While the
 * model warms up, a morphing ring runs around the microphone, which still swells with the voice
 * to show that it is being heard.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VoiceOrb(phase: PanelPhase, level: () -> Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shown = followLevel(level)
    val turn by rememberInfiniteTransition(label = "turn").animateFloat(
        0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "turn",
    )
    Box(modifier.size(132.dp), contentAlignment = Alignment.Center) {
        if (phase == PanelPhase.FINISHING) {
            LoadingIndicator(Modifier.size(84.dp))
            return@Box
        }
        if (phase == PanelPhase.WARMING) {
            LoadingIndicator(Modifier.size(128.dp), color = colors.primary.copy(alpha = 0.55f))
            Box(
                Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        val scale = 1f + shown.floatValue * 0.2f
                        scaleX = scale
                        scaleY = scale
                    }
                    .background(colors.primaryContainer, CircleShape),
            )
            Icon(Icons.Rounded.Mic, "Listening", tint = colors.onPrimaryContainer, modifier = Modifier.size(32.dp))
            return@Box
        }
        Box(
            Modifier
                .size(96.dp)
                .graphicsLayer {
                    val scale = 1f + shown.floatValue * 0.36f
                    scaleX = scale
                    scaleY = scale
                    rotationZ = -turn
                    alpha = 0.35f + shown.floatValue * 0.4f
                }
                .background(colors.primaryContainer, MaterialShapes.Cookie12Sided.toShape()),
        )
        Box(
            Modifier
                .size(84.dp)
                .graphicsLayer {
                    val scale = 1f + shown.floatValue * 0.14f
                    scaleX = scale
                    scaleY = scale
                    rotationZ = turn
                }
                .background(colors.primary, MaterialShapes.Cookie9Sided.toShape()),
        )
        Icon(Icons.Rounded.Mic, "Listening", tint = colors.onPrimary, modifier = Modifier.size(36.dp))
    }
}

/** A key that acts once when tapped and keeps acting while held, like backspace on a keyboard. */
@Composable
private fun RepeatingKey(icon: ImageVector, label: String, onPress: () -> Unit, size: Dp) {
    val colors = MaterialTheme.colorScheme
    val press by rememberUpdatedState(onPress)
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(size)
            .background(colors.secondaryContainer, CircleShape)
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick { press(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val repeat = scope.launch {
                        press()
                        delay(420)
                        while (true) {
                            press()
                            delay(60)
                        }
                    }
                    waitForUpOrCancellation()
                    repeat.cancel()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = colors.onSecondaryContainer)
    }
}

@Composable
private fun Problem(message: String, onOpenApp: () -> Unit, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Spacer(Modifier.height(18.dp))
    Text(message, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, textAlign = TextAlign.Center)
    Spacer(Modifier.height(20.dp))
    Button(onClick = onOpenApp, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Open Alyo", style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(8.dp))
    FilledTonalButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Icon(Icons.Rounded.Keyboard, null, Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text("Back to keyboard", style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(8.dp))
}
