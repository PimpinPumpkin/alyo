package app.sotto.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.sotto.audio.AudioSpec
import app.sotto.audio.Peaks
import app.sotto.ui.formatStamp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private const val BAR_MS = 160
private val BAR_PITCH = 11.dp
private const val PEAKS_PER_BAR = BAR_MS / AudioSpec.PEAK_MS

/** Below this a stretch counts as silence and is drawn as a dot rather than a bar (about -38 dB). */
private const val SILENCE = 0.3f

/**
 * Stored levels are decibels, which is right for keeping quiet speech visible but draws loud
 * material as a solid block. Cubing spreads the top of the range back out: -6 dB is about 70% of
 * full height, -20 dB a quarter.
 */
private fun shaped(level: Float): Float = level * level * level

private enum class Grab { SCRUB, START, END }

/**
 * The waveform as a tape running under a fixed playhead in the middle of the view: audio already
 * heard is to the left, audio to come is to the right. While recording, the newest bar is always
 * at the playhead. Speech is drawn as bars and silence as a row of dots.
 *
 * @param peakCount how many bars [peaks] holds; passed separately so growth triggers a redraw
 * @param positionMs read during drawing, so moving the playhead never recomposes anything
 * @param onScrub called with a time delta as the user drags the tape; null makes it non-interactive
 * @param selection when given, a frame with two handles is drawn over that stretch, and dragging
 *   a handle reports the new edges through [onSelection]
 */
@Composable
fun TapeWaveform(
    peaks: Peaks,
    peakCount: Int,
    positionMs: () -> Long,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    silenceColor: Color = MaterialTheme.colorScheme.onSurface,
    playheadColor: Color = MaterialTheme.colorScheme.onSurface,
    onScrub: ((deltaMs: Long) -> Unit)? = null,
    selection: (() -> Pair<Long, Long>)? = null,
    onSelection: (startMs: Long, endMs: Long) -> Unit = { _, _ -> },
) {
    val frameColor = MaterialTheme.colorScheme.inverseSurface
    val gripColor = MaterialTheme.colorScheme.inverseOnSurface
    val scrub = rememberUpdatedState(onScrub)
    val select = rememberUpdatedState(onSelection)
    val currentSelection = rememberUpdatedState(selection)
    val position = rememberUpdatedState(positionMs)

    Canvas(
        modifier.pointerInput(onScrub != null) {
            if (scrub.value == null) return@pointerInput
            val pitch = BAR_PITCH.toPx()
            val reach = 32.dp.toPx()
            var grab = Grab.SCRUB
            detectHorizontalDragGestures(
                onDragStart = { offset ->
                    grab = Grab.SCRUB
                    currentSelection.value?.invoke()?.let { (start, end) ->
                        fun x(ms: Long) = size.width / 2f + (ms - position.value()) / BAR_MS.toFloat() * pitch
                        val toStart = abs(offset.x - x(start))
                        val toEnd = abs(offset.x - x(end))
                        if (minOf(toStart, toEnd) < reach) grab = if (toStart <= toEnd) Grab.START else Grab.END
                    }
                },
            ) { change, drag ->
                change.consume()
                val deltaMs = (drag / pitch * BAR_MS).toLong()
                val current = currentSelection.value?.invoke()
                when {
                    grab == Grab.START && current != null -> select.value(current.first + deltaMs, current.second)
                    grab == Grab.END && current != null -> select.value(current.first, current.second + deltaMs)
                    else -> scrub.value?.invoke(-deltaMs)
                }
            }
        },
    ) {
        val pitch = BAR_PITCH.toPx()
        val barWidth = 6.dp.toPx()
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val maxBar = size.height * 0.4f
        val now = positionMs()
        val bars = ceil(peakCount / PEAKS_PER_BAR.toFloat()).toInt()
        fun x(ms: Long) = centerX + (ms - now) / BAR_MS.toFloat() * pitch

        val firstVisible = maxOf(0, floor((now - centerX / pitch * BAR_MS) / BAR_MS).toInt() - 1)
        val lastVisible = minOf(bars - 1, ceil((now + centerX / pitch * BAR_MS) / BAR_MS).toInt() + 1)
        for (bar in firstVisible..lastVisible) {
            val barX = x(bar.toLong() * BAR_MS) + pitch / 2f
            var level = 0f
            for (p in bar * PEAKS_PER_BAR until (bar + 1) * PEAKS_PER_BAR) level = maxOf(level, peaks.level(p))
            if (level < SILENCE) {
                drawCircle(silenceColor, radius = 2.5.dp.toPx(), center = Offset(barX, centerY))
            } else {
                val height = maxOf(12.dp.toPx(), shaped(level) * maxBar)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(barX - barWidth / 2f, centerY - height / 2f),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }

        selection?.invoke()?.let { (start, end) -> drawSelectionFrame(x(start), x(end), size.height * 0.22f, size.height * 0.78f, frameColor, gripColor) }

        drawLine(
            playheadColor,
            Offset(centerX, size.height * 0.26f),
            Offset(centerX, size.height * 0.74f),
            strokeWidth = 5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** The bright rounded frame with a thick handle at each end that marks a selection. */
private fun DrawScope.drawSelectionFrame(left: Float, right: Float, top: Float, bottom: Float, frame: Color, grip: Color, handle: Float = 16.dp.toPx()) {
    val l = minOf(left, right)
    val r = maxOf(right, l + 1f)
    val radius = CornerRadius(8.dp.toPx())
    val stroke = 3.dp.toPx()
    drawRoundRect(frame, Offset(l - handle, top), Size(r - l + handle * 2, bottom - top), radius, style = Stroke(stroke))
    for (edge in listOf(l - handle, r)) {
        drawRoundRect(frame, Offset(edge, top), Size(handle, bottom - top), radius)
        val gripHeight = minOf(28.dp.toPx(), (bottom - top) * 0.4f)
        val middle = (top + bottom) / 2f
        drawLine(grip, Offset(edge + handle / 2f, middle - gripHeight / 2f), Offset(edge + handle / 2f, middle + gripHeight / 2f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

/**
 * The thin progress line under the content card. Stretches with speech are bright and silences
 * dim, so the shape of the conversation is visible at a glance. Tap or drag to seek.
 *
 * With [selection] it also shows the selected stretch as a framed box whose ends can be dragged,
 * and a small time bubble riding above the playhead.
 */
@Composable
fun SeekBar(
    peaks: Peaks,
    durationMs: Long,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    selection: (() -> Pair<Long, Long>)? = null,
    onSelection: (startMs: Long, endMs: Long) -> Unit = { _, _ -> },
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val bubbleStyle = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
    val seek = rememberUpdatedState(onSeek)
    val select = rememberUpdatedState(onSelection)
    val currentSelection = rememberUpdatedState(selection)
    val bubble = selection != null

    Canvas(
        modifier
            .fillMaxWidth()
            .height(if (bubble) 92.dp else 48.dp)
            .pointerInput(durationMs) {
                detectTapGestures { seek.value(msAt(it.x, size.width.toFloat(), 12.dp.toPx(), durationMs)) }
            }
            .pointerInput(durationMs) {
                val inset = 12.dp.toPx()
                val reach = 28.dp.toPx()
                var grab = Grab.SCRUB
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        grab = Grab.SCRUB
                        currentSelection.value?.invoke()?.let { (start, end) ->
                            val toStart = abs(offset.x - xAt(start, size.width.toFloat(), inset, durationMs))
                            val toEnd = abs(offset.x - xAt(end, size.width.toFloat(), inset, durationMs))
                            if (minOf(toStart, toEnd) < reach) grab = if (toStart <= toEnd) Grab.START else Grab.END
                        }
                    },
                ) { change, _ ->
                    change.consume()
                    val ms = msAt(change.position.x, size.width.toFloat(), inset, durationMs)
                    val current = currentSelection.value?.invoke()
                    when {
                        grab == Grab.START && current != null -> select.value(ms, current.second)
                        grab == Grab.END && current != null -> select.value(current.first, ms)
                        else -> seek.value(ms)
                    }
                }
            },
    ) {
        if (durationMs <= 0) return@Canvas
        val inset = 12.dp.toPx()
        val trackY = size.height - 24.dp.toPx()
        val width = size.width - inset * 2

        // Track, in short runs coloured by whether anyone is speaking there.
        val step = 3.dp.toPx()
        val runs = (width / step).toInt().coerceAtLeast(1)
        val perRun = peaks.size / runs.toFloat()
        var runStart = 0
        var runSpeech = false
        fun flush(until: Int) {
            if (until <= runStart) return
            drawLine(
                if (runSpeech) colors.primary else colors.outlineVariant,
                Offset(inset + runStart * step, trackY),
                Offset(inset + until * step, trackY),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        for (run in 0 until runs) {
            var level = 0f
            val first = (run * perRun).toInt()
            for (p in first until maxOf(first + 1, ((run + 1) * perRun).toInt())) level = maxOf(level, peaks.level(p))
            val speech = level >= SILENCE
            if (run == 0) runSpeech = speech
            if (speech != runSpeech) {
                flush(run)
                runStart = run
                runSpeech = speech
            }
        }
        flush(runs)

        selection?.invoke()?.let { (start, end) ->
            drawSelectionFrame(
                xAt(start, size.width, inset, durationMs),
                xAt(end, size.width, inset, durationMs),
                trackY - 20.dp.toPx(),
                trackY + 20.dp.toPx(),
                colors.inverseSurface,
                colors.inverseOnSurface,
                handle = 10.dp.toPx(),
            )
        }

        val now = positionMs()
        val thumbX = xAt(now, size.width, inset, durationMs)
        val thumbHeight = 34.dp.toPx()
        val thumbWidth = 6.dp.toPx()
        drawRoundRect(
            colors.onSurface,
            Offset(thumbX - thumbWidth / 2f, trackY - thumbHeight / 2f),
            Size(thumbWidth, thumbHeight),
            CornerRadius(thumbWidth / 2f),
        )

        if (bubble) {
            val label = measurer.measure(formatStamp(now), bubbleStyle)
            val padX = 12.dp.toPx()
            val padY = 6.dp.toPx()
            val boxWidth = label.size.width + padX * 2
            val boxHeight = label.size.height + padY * 2
            val left = (thumbX - boxWidth / 2f).coerceIn(0f, size.width - boxWidth)
            drawRoundRect(colors.inverseSurface, Offset(left, 0f), Size(boxWidth, boxHeight), CornerRadius(boxHeight / 2f))
            drawText(label, colors.inverseOnSurface, Offset(left + padX, padY))
        }
    }
}

private fun xAt(ms: Long, width: Float, inset: Float, durationMs: Long): Float =
    inset + (ms.toFloat() / durationMs).coerceIn(0f, 1f) * (width - inset * 2)

private fun msAt(x: Float, width: Float, inset: Float, durationMs: Long): Long =
    (((x - inset) / (width - inset * 2)).coerceIn(0f, 1f) * durationMs).toLong()
