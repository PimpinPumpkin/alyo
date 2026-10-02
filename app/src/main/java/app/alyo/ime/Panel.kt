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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
 * What takes the keyboard's place while dictating: a shape that moves with the voice, with undo
 * and backspace either side and Done below. The words themselves appear in the text field.
 *
 * @param problem why dictation cannot start (no model, no permission), or null
 * @param level how loud the microphone is, 0 to 1, read on every frame
 */
@Composable
fun DictationPanel(
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
    Surface(color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 24.dp).padding(top = 14.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (problem != null) {
                Problem(problem, onOpenApp, onDone)
                return@Column
            }
            AnimatedContent(phase, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") { now ->
                Text(
                    when (now) {
                        // Said plainly, because nothing appears in the field yet and that reads as broken.
                        // The microphone is open and nothing said now is lost; the words follow
                        // as soon as the model is up. So this is listening, and says so.
                        PanelPhase.WARMING -> "Listening"
                        PanelPhase.LISTENING -> "Listening"
                        PanelPhase.FINISHING -> "Finishing"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, "Undo the last sentence")
                }
                VoiceOrb(phase = phase, level = level)
                RepeatingKey(Icons.AutoMirrored.Rounded.Backspace, "Backspace", onBackspace)
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onDone,
                enabled = phase != PanelPhase.FINISHING,
                contentPadding = ButtonDefaults.ContentPadding,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Rounded.Check, null, Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Done", style = MaterialTheme.typography.titleMedium)
            }
        }
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
    val currentLevel by rememberUpdatedState(level)
    // Followed by hand, once a frame: quick to rise with a word and slower to fall, like a meter.
    val shown = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            val target = currentLevel()
            val now = shown.floatValue
            shown.floatValue = now + (target - now) * (if (target > now) 0.45f else 0.08f)
        }
    }
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
private fun RepeatingKey(icon: ImageVector, label: String, onPress: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val press by rememberUpdatedState(onPress)
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(56.dp)
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
