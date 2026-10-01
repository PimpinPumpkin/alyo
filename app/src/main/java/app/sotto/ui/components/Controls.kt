package app.sotto.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sotto.ui.theme.LocalSottoColors

/**
 * The waveform / transcript switch that sits under the content card: two icons in a dark capsule,
 * with a bright disc that slides to whichever is showing.
 */
@Composable
fun ViewToggle(showTranscript: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val cell = 56.dp
    val thumbX by animateDpAsState(if (showTranscript) cell else 0.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "thumb")
    Box(modifier.size(width = cell * 2, height = cell).clip(CircleShape).background(colors.surfaceContainerHighest)) {
        Box(Modifier.offset(x = thumbX).size(cell).background(colors.inverseSurface, CircleShape))
        Row {
            ToggleCell(Icons.Rounded.GraphicEq, "Audio", selected = !showTranscript, cell) { onChange(false) }
            ToggleCell(Icons.AutoMirrored.Rounded.Notes, "Transcript", selected = showTranscript, cell) { onChange(true) }
        }
    }
}

@Composable
private fun ToggleCell(icon: ImageVector, label: String, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Tab
                this.selected = selected
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (selected) colors.inverseOnSurface else colors.onSurfaceVariant)
    }
}

/** The wide Play / Pause pill at the center of the transport row. */
@Composable
fun RowScope.PlayPill(playing: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Squares off under the finger and while playing: Material 3 Expressive shows state through shape.
    val corner by animateDpAsState(
        when {
            pressed -> 20.dp
            playing -> 32.dp
            else -> 48.dp
        },
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "corner",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = colors.primary,
        contentColor = colors.onPrimary,
        interactionSource = interaction,
        modifier = Modifier.weight(1f).height(96.dp),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text(if (playing) "Pause" else "Play", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

/** The round skip buttons either side of [PlayPill]. */
@Composable
fun RoundControl(icon: ImageVector, description: String, onClick: () -> Unit, size: Dp = 80.dp) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = CircleShape, color = colors.surfaceContainerHigh, contentColor = colors.onSurface, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, Modifier.size(30.dp)) }
    }
}

/** One line of a grouped bottom-sheet list. */
class SheetRow(
    val icon: ImageVector,
    val label: String,
    val detail: String? = null,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val trailing: (@Composable () -> Unit)? = null,
    val onClick: () -> Unit,
)

/**
 * Rows fused into one rounded block with hairline gaps between them, the list style the sheets
 * use: the block's outer corners are large, the corners where rows meet are small.
 */
@Composable
fun SheetGroup(rows: List<SheetRow>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val outer = 24.dp
    val inner = 4.dp
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, row ->
            val top = if (index == 0) outer else inner
            val bottom = if (index == rows.lastIndex) outer else inner
            val tint = when {
                !row.enabled -> colors.onSurface.copy(alpha = 0.38f)
                row.danger -> colors.error
                else -> colors.onSurface
            }
            Surface(
                onClick = row.onClick,
                enabled = row.enabled,
                shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                color = colors.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(row.icon, null, tint = tint)
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.label, style = MaterialTheme.typography.titleMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        row.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
                    }
                    row.trailing?.invoke()
                }
            }
        }
    }
}

enum class RecordGlyph { RECORD, PAUSE }

/**
 * The record button: a red disc with a dark dot. Pressing squeezes the disc and squares the dot
 * off, the springy shape change Material 3 Expressive uses for its primary actions.
 */
@Composable
fun RecordButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 104.dp,
    glyph: RecordGlyph = RecordGlyph.RECORD,
    description: String = "Record",
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = MaterialTheme.motionScheme
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, motion.fastSpatialSpec(), label = "scale")
    val corner by animateDpAsState(if (pressed) size * 0.07f else size * 0.13f, motion.fastSpatialSpec(), label = "corner")
    val sotto = LocalSottoColors.current

    Surface(
        onClick = onClick,
        modifier = modifier.size(size).scale(scale).semantics { contentDescription = description },
        shape = CircleShape,
        color = sotto.record,
        shadowElevation = 6.dp,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (glyph) {
                RecordGlyph.RECORD -> Box(Modifier.size(size * 0.26f).background(sotto.onRecord, RoundedCornerShape(corner)))
                RecordGlyph.PAUSE -> Row(horizontalArrangement = Arrangement.spacedBy(size * 0.09f)) {
                    repeat(2) { Box(Modifier.size(width = size * 0.1f, height = size * 0.32f).background(sotto.onRecord, RoundedCornerShape(50))) }
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
