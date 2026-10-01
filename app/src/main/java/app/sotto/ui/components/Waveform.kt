package app.sotto.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.sotto.audio.AudioSpec
import app.sotto.audio.Peaks
import app.sotto.ui.formatClock
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Stored levels are decibels, which is right for keeping quiet speech visible but draws loud
 * material as a solid block. Cubing spreads the top of the range back out: -6 dB is about 70% of
 * full height, -20 dB a quarter, and room tone falls to the minimum bar.
 */
private fun shaped(level: Float): Float = level * level * level

private const val BAR_MS = 100
private const val PEAKS_PER_BAR = BAR_MS / AudioSpec.PEAK_MS

/**
 * The waveform as a tape running under a fixed playhead in the middle of the view: audio already
 * heard is to the left, audio to come is to the right. While recording, the newest bar is always
 * at the playhead and the right half is the empty tape still to be filled.
 *
 * @param peakCount how many bars [peaks] holds; passed separately so growth triggers a redraw
 * @param positionMs read during drawing, so moving the playhead never recomposes anything
 * @param onScrub called with a time delta as the user drags the tape; null makes it non-interactive
 */
@Composable
fun TapeWaveform(
    peaks: Peaks,
    peakCount: Int,
    positionMs: () -> Long,
    modifier: Modifier = Modifier,
    playedColor: Color = MaterialTheme.colorScheme.primary,
    upcomingColor: Color = MaterialTheme.colorScheme.outlineVariant,
    playheadColor: Color = MaterialTheme.colorScheme.primary,
    onScrubStart: () -> Unit = {},
    onScrub: ((deltaMs: Long) -> Unit)? = null,
    onScrubEnd: () -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tickColor = MaterialTheme.colorScheme.outlineVariant
    val scrub = rememberUpdatedState(onScrub)
    val scrubStart = rememberUpdatedState(onScrubStart)
    val scrubEnd = rememberUpdatedState(onScrubEnd)

    Canvas(
        modifier.pointerInput(onScrub != null) {
            if (scrub.value == null) return@pointerInput
            val pitch = 5.dp.toPx()
            detectHorizontalDragGestures(
                onDragStart = { scrubStart.value() },
                onDragEnd = { scrubEnd.value() },
                onDragCancel = { scrubEnd.value() },
            ) { change, drag ->
                change.consume()
                scrub.value?.invoke((-drag / pitch * BAR_MS).toLong())
            }
        },
    ) {
        val pitch = 5.dp.toPx()
        val barWidth = 3.dp.toPx()
        val ruler = 22.dp.toPx()
        val centerX = size.width / 2f
        val centerY = ruler + (size.height - ruler) / 2f
        val maxBar = (size.height - ruler) * 0.86f
        val minBar = 3.dp.toPx()
        val position = positionMs()
        val bars = ceil(peakCount / PEAKS_PER_BAR.toFloat()).toInt()

        val firstVisible = floor((position - centerX / pitch * BAR_MS) / BAR_MS).toInt() - 1
        val lastVisible = ceil((position + centerX / pitch * BAR_MS) / BAR_MS).toInt() + 1

        // Ruler: a tick every second, a time every two.
        val firstSecond = maxOf(0, firstVisible * BAR_MS / 1000)
        val lastSecond = lastVisible * BAR_MS / 1000 + 1
        for (second in firstSecond..lastSecond) {
            val x = centerX + (second * 1000L - position) / BAR_MS.toFloat() * pitch
            drawLine(tickColor, Offset(x, ruler - 6.dp.toPx()), Offset(x, ruler - 2.dp.toPx()), strokeWidth = 1.dp.toPx())
            if (second % 2 == 0) {
                val layout = measurer.measure(formatClock(second * 1000L), labelStyle)
                val left = x - layout.size.width / 2f
                if (left > 0 && left + layout.size.width < size.width) {
                    drawText(layout, labelColor, Offset(left, 0f))
                }
            }
        }

        for (bar in maxOf(0, firstVisible)..lastVisible) {
            val x = centerX + (bar * BAR_MS - position) / BAR_MS.toFloat() * pitch
            if (bar >= bars) {
                // Tape not yet recorded: a dotted baseline.
                drawCircle(tickColor, radius = 1.dp.toPx(), center = Offset(x, centerY))
                continue
            }
            var level = 0f
            for (p in bar * PEAKS_PER_BAR until (bar + 1) * PEAKS_PER_BAR) level = maxOf(level, peaks.level(p))
            val height = maxOf(minBar, shaped(level) * maxBar)
            drawRoundRect(
                color = if (bar * BAR_MS < position) playedColor else upcomingColor,
                topLeft = Offset(x - barWidth / 2f, centerY - height / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }

        drawLine(
            playheadColor,
            Offset(centerX, ruler),
            Offset(centerX, size.height),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(playheadColor, radius = 4.dp.toPx(), center = Offset(centerX, ruler))
    }
}

/**
 * The whole recording squeezed into the width of the view, for trimming and for the thin progress
 * strip. The stretch between [selectionStartMs] and [selectionEndMs] is drawn at full strength
 * and everything outside it faded.
 */
@Composable
fun OverviewWaveform(
    peaks: Peaks,
    durationMs: Long,
    modifier: Modifier = Modifier,
    positionMs: (() -> Long)? = null,
    selectionStartMs: () -> Long = { 0L },
    selectionEndMs: () -> Long = { durationMs },
    color: Color = MaterialTheme.colorScheme.primary,
    fadedColor: Color = MaterialTheme.colorScheme.outlineVariant,
    playheadColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Canvas(modifier) {
        if (durationMs <= 0 || peaks.size == 0) return@Canvas
        val pitch = 4.dp.toPx()
        val barWidth = 2.5.dp.toPx()
        val bars = (size.width / pitch).toInt().coerceAtLeast(1)
        val perBar = peaks.size / bars.toFloat()
        val centerY = size.height / 2f
        // Read here, in the draw phase, so dragging a handle or playing redraws without recomposing.
        val from = selectionStartMs()
        val until = selectionEndMs()
        for (bar in 0 until bars) {
            var level = 0f
            val first = (bar * perBar).toInt()
            val last = maxOf(first + 1, ((bar + 1) * perBar).toInt())
            for (p in first until last) level = maxOf(level, peaks.level(p))
            val height = maxOf(2.dp.toPx(), shaped(level) * size.height * 0.9f)
            val timeMs = (bar + 0.5f) / bars * durationMs
            drawRoundRect(
                color = if (timeMs >= from && timeMs <= until) color else fadedColor,
                topLeft = Offset(bar * pitch + (pitch - barWidth) / 2f, centerY - height / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
        positionMs?.let {
            val x = (it().toFloat() / durationMs).coerceIn(0f, 1f) * size.width
            drawLine(playheadColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
