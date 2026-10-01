package app.sotto.ui.components

import androidx.compose.foundation.Canvas
import kotlinx.coroutines.withTimeoutOrNull
import app.sotto.ui.formatClockFine
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import kotlin.math.pow

private const val BAR_MS = 160
private val BAR_PITCH = 11.dp
private const val PEAKS_PER_BAR = BAR_MS / AudioSpec.PEAK_MS

/** Below this, relative to the recording's own range, a stretch is drawn as silence. */
private const val SILENCE = 0.18f

/** Eases the bottom of the range down so quiet speech reads as quiet rather than half height. */
private fun shaped(relative: Float): Float = relative.pow(1.5f)

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
            for (p in bar * PEAKS_PER_BAR until (bar + 1) * PEAKS_PER_BAR) level = maxOf(level, peaks.relative(p))
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
 * with a small bubble above it giving the length of the selection.
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
    val haptics = LocalHapticFeedback.current
    // While a handle is held still, the bar stops showing the whole recording and shows only a
    // few seconds around the handle, so a finger's width covers a fraction of a second.
    var zoom by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    val magnify by animateFloatAsState(if (zoom != null) 1f else 0f, label = "zoom")

    Canvas(
        modifier
            .fillMaxWidth()
            .height(if (selection != null) 92.dp else 48.dp)
            .pointerInput(durationMs, selection != null) {
                val inset = 12.dp.toPx()
                val reach = 28.dp.toPx()
                val still = 6.dp.toPx()
                fun window() = zoom ?: (0L to durationMs)
                fun xOf(ms: Long): Float = window().let { (from, to) -> inset + ((ms - from).toFloat() / (to - from)).coerceIn(0f, 1f) * (size.width - inset * 2) }
                fun msOf(x: Float): Long = window().let { (from, to) -> from + (((x - inset) / (size.width - inset * 2)).coerceIn(0f, 1f) * (to - from)).toLong() }

                awaitEachGesture {
                    val down = awaitFirstDown()
                    var grab = Grab.SCRUB
                    currentSelection.value?.invoke()?.let { (start, end) ->
                        val toStart = abs(down.position.x - xOf(start))
                        val toEnd = abs(down.position.x - xOf(end))
                        if (minOf(toStart, toEnd) < reach) grab = if (toStart <= toEnd) Grab.START else Grab.END
                    }
                    fun apply(x: Float) {
                        val ms = msOf(x)
                        val current = currentSelection.value?.invoke()
                        when {
                            grab == Grab.START && current != null -> select.value(ms, current.second)
                            grab == Grab.END && current != null -> select.value(current.first, ms)
                            else -> seek.value(ms)
                        }
                    }
                    if (grab == Grab.SCRUB) apply(down.position.x)
                    var anchor = down.position
                    var restedAt = down.uptimeMillis
                    try {
                        while (true) {
                            // Waking up with no event means the finger has not moved at all.
                            val event = withTimeoutOrNull(HOLD_MS) { awaitPointerEvent() }
                            val change = event?.changes?.firstOrNull { it.id == down.id }
                            if (event != null && (change == null || !change.pressed)) break
                            val now = change?.uptimeMillis ?: (restedAt + HOLD_MS)
                            if (change != null && (change.position - anchor).getDistance() > still) {
                                anchor = change.position
                                restedAt = now
                            }
                            if (zoom == null && grab != Grab.SCRUB && now - restedAt >= HOLD_MS && durationMs > ZOOM_SPAN_MS * 2) {
                                val (start, end) = currentSelection.value?.invoke() ?: continue
                                val center = if (grab == Grab.START) start else end
                                // Keep the handle under the finger: the window is placed so the
                                // handle's time sits at the finger's current x.
                                val fraction = ((anchor.x - inset) / (size.width - inset * 2)).coerceIn(0f, 1f)
                                val from = (center - (fraction * ZOOM_SPAN_MS).toLong()).coerceIn(0, durationMs - ZOOM_SPAN_MS)
                                zoom = from to from + ZOOM_SPAN_MS
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            if (change != null) {
                                change.consume()
                                apply(change.position.x)
                            }
                        }
                    } finally {
                        zoom = null
                    }
                }
            },
    ) {
        if (durationMs <= 0) return@Canvas
        val inset = 12.dp.toPx()
        val trackY = size.height - 24.dp.toPx()
        val (from, to) = zoom ?: (0L to durationMs)
        fun x(ms: Long) = inset + ((ms - from).toFloat() / (to - from)).coerceIn(0f, 1f) * (size.width - inset * 2)

        drawSpeechTrack(peaks, inset, size.width - inset, trackY, colors.primary, colors.outlineVariant, from, to, durationMs, thickness = (4f + 4f * magnify).dp.toPx())

        selection?.invoke()?.let { (start, end) ->
            drawSelectionFrame(x(start), x(end), trackY - 20.dp.toPx(), trackY + 20.dp.toPx(), colors.inverseSurface, colors.inverseOnSurface, handle = 10.dp.toPx())
        }

        val now = positionMs()
        if (now in from..to) {
            val thumbX = x(now)
            val thumbHeight = 34.dp.toPx()
            val thumbWidth = 6.dp.toPx()
            drawRoundRect(colors.onSurface, Offset(thumbX - thumbWidth / 2f, trackY - thumbHeight / 2f), Size(thumbWidth, thumbHeight), CornerRadius(thumbWidth / 2f))
        }

        // The bubble rides over the middle of the selection and says how long it is. Zoomed in,
        // tenths of a second matter, so it shows them.
        selection?.invoke()?.let { (start, end) ->
            val label = measurer.measure(if (zoom != null) formatClockFine(end - start) else formatStamp(end - start), bubbleStyle)
            val padX = 12.dp.toPx()
            val padY = 6.dp.toPx()
            val boxWidth = label.size.width + padX * 2
            val boxHeight = label.size.height + padY * 2
            val middle = (x(start) + x(end)) / 2f
            val left = (middle - boxWidth / 2f).coerceIn(0f, size.width - boxWidth)
            drawRoundRect(colors.inverseSurface, Offset(left, 0f), Size(boxWidth, boxHeight), CornerRadius(boxHeight / 2f))
            drawText(label, colors.inverseOnSurface, Offset(left + padX, padY))
        }
    }
}

/** How long a handle must be held still before the seek bar zooms in, and how much it then shows. */
private const val HOLD_MS = 450L
private const val ZOOM_SPAN_MS = 6_000L

/**
 * A line from [left] to [right], bright over the stretches where someone is speaking and dim over
 * silence. It shows the recording between [fromMs] and [toMs] of [durationMs]; the defaults are
 * the whole of it.
 */
private fun DrawScope.drawSpeechTrack(
    peaks: Peaks,
    left: Float,
    right: Float,
    y: Float,
    speech: Color,
    silence: Color,
    fromMs: Long = 0,
    toMs: Long = 1,
    durationMs: Long = 1,
    thickness: Float = 4.dp.toPx(),
) {
    drawLine(silence, Offset(left, y), Offset(right, y), strokeWidth = thickness, cap = StrokeCap.Round)
    if (peaks.size == 0) return
    val step = 3.dp.toPx()
    val runs = ((right - left) / step).toInt().coerceAtLeast(1)
    val firstPeak = peaks.size * (fromMs.toFloat() / durationMs)
    val perRun = peaks.size * ((toMs - fromMs).toFloat() / durationMs) / runs
    var runStart = -1
    for (run in 0..runs) {
        var level = 0f
        if (run < runs) {
            val first = (firstPeak + run * perRun).toInt()
            for (p in first until maxOf(first + 1, (firstPeak + (run + 1) * perRun).toInt())) level = maxOf(level, peaks.relative(p))
        }
        val speaking = run < runs && level >= SILENCE
        if (speaking && runStart < 0) runStart = run
        if (!speaking && runStart >= 0) {
            drawLine(speech, Offset(left + runStart * step, y), Offset(minOf(right, left + run * step), y), strokeWidth = thickness, cap = StrokeCap.Butt)
            runStart = -1
        }
    }
}

/** The speech-and-silence line on its own, as shown on each recording in the library. */
@Composable
fun SpeechBar(peaks: Peaks?, modifier: Modifier = Modifier) {
    val speech = MaterialTheme.colorScheme.primary
    val silence = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val inset = 2.dp.toPx()
        if (peaks == null) {
            drawLine(silence, Offset(inset, size.height / 2f), Offset(size.width - inset, size.height / 2f), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        } else {
            drawSpeechTrack(peaks, inset, size.width - inset, size.height / 2f, speech, silence)
        }
    }
}
