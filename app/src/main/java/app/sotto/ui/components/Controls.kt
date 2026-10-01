package app.sotto.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sotto.ui.theme.LocalSottoColors

/** The Audio / Transcript switch at the top of the record and playback screens. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ViewToggle(showTranscript: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        ToggleButton(
            checked = !showTranscript,
            onCheckedChange = { onChange(false) },
            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
            modifier = Modifier.semantics { role = Role.RadioButton },
        ) {
            Icon(Icons.Rounded.GraphicEq, null, Modifier.size(ToggleButtonDefaults.IconSize))
            Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
            Text("Audio")
        }
        ToggleButton(
            checked = showTranscript,
            onCheckedChange = { onChange(true) },
            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
            modifier = Modifier.semantics { role = Role.RadioButton },
        ) {
            Icon(Icons.AutoMirrored.Rounded.Notes, null, Modifier.size(ToggleButtonDefaults.IconSize))
            Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
            Text("Transcript")
        }
    }
}

enum class RecordGlyph { RECORD, PAUSE }

/**
 * The record button: a pale disc holding the red dot. Pressing squeezes the disc and squares the
 * dot off, the springy shape change Material 3 Expressive uses for its primary actions.
 */
@Composable
fun RecordButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    glyph: RecordGlyph = RecordGlyph.RECORD,
    description: String = "Record",
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = MaterialTheme.motionScheme
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, motion.fastSpatialSpec(), label = "scale")
    val corner by animateDpAsState(if (pressed) size * 0.11f else size * 0.2f, motion.fastSpatialSpec(), label = "corner")
    val red = LocalSottoColors.current.record

    Surface(
        onClick = onClick,
        modifier = modifier.size(size).scale(scale).semantics { contentDescription = description },
        shape = CircleShape,
        // The disc has to stand off the page: the brightest surface in light theme, a raised one in dark.
        color = if (LocalSottoColors.current.dark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 6.dp,
        tonalElevation = 0.dp,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (glyph) {
                RecordGlyph.RECORD -> Box(Modifier.size(size * 0.4f).background(red, RoundedCornerShape(corner)))
                RecordGlyph.PAUSE -> Row(horizontalArrangement = Arrangement.spacedBy(size * 0.09f)) {
                    repeat(2) { Box(Modifier.size(width = size * 0.11f, height = size * 0.36f).background(red, RoundedCornerShape(50))) }
                }
            }
        }
    }
}

/** A small filled dot, used to mark "recording" in text rows. */
@Composable
fun RecordingDot(modifier: Modifier = Modifier, color: Color = LocalSottoColors.current.record) {
    Box(modifier.size(10.dp).background(color, CircleShape))
}
