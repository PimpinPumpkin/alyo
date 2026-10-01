package app.sotto.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sotto.playback.Player
import app.sotto.ui.components.PlayPill
import app.sotto.ui.components.RoundControl
import app.sotto.ui.formatStamp

/** The rounded panel that holds the waveform or the transcript on the player, crop and record screens. */
@Composable
fun ContentCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Box { content() }
    }
}

/** Elapsed time on the left, time remaining (as a negative) on the right, under the seek bar. */
@Composable
fun TimeLabels(positionMs: () -> Long, durationMs: Long) {
    // Whole seconds only, so this recomposes once a second rather than once a frame.
    val seconds by remember(durationMs) { derivedStateOf { positionMs() / 1000 } }
    val style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")
    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatStamp(seconds * 1000), style = style)
        Text("-" + formatStamp((durationMs - seconds * 1000).coerceAtLeast(0)), style = style)
    }
}

/**
 * Skip back, the Play pill, skip forward. The player uses 5 s back and 10 s forward; crop mode
 * passes [fine] for one-second steps and takes over [onPlayPause] to keep playback inside the selection.
 */
@Composable
fun TransportRow(player: Player, fine: Boolean = false, onPlayPause: () -> Unit = player::toggle) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (fine) {
            RoundControl(Icons.Rounded.Replay, "Back 1 second", onClick = { player.seekBy(-1_000) })
        } else {
            RoundControl(Icons.Rounded.Replay5, "Back 5 seconds", onClick = { player.seekBy(-5_000) })
        }
        Spacer(Modifier.width(12.dp))
        PlayPill(player.isPlaying, onClick = onPlayPause)
        Spacer(Modifier.width(12.dp))
        if (fine) {
            RoundControl(Icons.Rounded.Refresh, "Forward 1 second", onClick = { player.seekBy(1_000) })
        } else {
            RoundControl(Icons.Rounded.Forward10, "Forward 10 seconds", onClick = { player.seekBy(10_000) })
        }
    }
}

/** True when the window is wider than it is tall, or simply wide: a phone on its side, a tablet, a foldable opened. */
@Composable
fun isWide(): Boolean {
    val config = LocalConfiguration.current
    return config.screenWidthDp >= 840 || (config.screenWidthDp > config.screenHeightDp && config.screenWidthDp >= 560)
}

/**
 * The two halves of the player, crop and record screens. On a tall screen [content] sits above
 * [controls]; on a wide one they sit side by side, so the transcript keeps its height instead of
 * being squeezed into a strip.
 */
@Composable
fun ColumnScope.AdaptivePane(content: @Composable ColumnScope.() -> Unit, controls: @Composable ColumnScope.() -> Unit) {
    if (isWide()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(bottom = 12.dp), content = content)
            Column(Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, content = controls)
        }
    } else {
        Column(Modifier.weight(1f).fillMaxWidth(), content = content)
        controls()
    }
}
