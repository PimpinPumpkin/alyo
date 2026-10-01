package app.sotto.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.sotto.data.Segment
import app.sotto.data.Word
import app.sotto.ui.formatClock

/**
 * A paragraph on screen: one or more sentences run together. Sentences are short (they end at
 * every pause), so showing each on its own line would read like a list rather than prose.
 */
@Immutable
class Paragraph(val segments: List<Segment>, val live: Boolean = false) {
    val words: List<Word> = segments.flatMap { it.words }
    val startMs: Int = words.firstOrNull()?.startMs ?: 0
    val endMs: Int = words.lastOrNull()?.endMs ?: 0
    val key: Long = segments.firstOrNull()?.id ?: -1L

    /** Character range of each word in [text], for mapping taps and highlights. */
    val offsets: IntArray = IntArray(words.size + 1)
    val text: String = buildString {
        words.forEachIndexed { i, word ->
            if (i > 0) append(' ')
            offsets[i] = length
            append(word.text)
        }
        offsets[words.size] = length + 1
    }

    fun wordAtChar(offset: Int): Int {
        for (i in words.indices) if (offset < offsets[i + 1] - 1) return i
        return words.lastIndex
    }

    /** Index of the word being spoken at [ms], or -1 if playback is outside this paragraph. */
    fun wordAt(ms: Long): Int {
        if (words.isEmpty() || ms < startMs || ms >= endMs) return -1
        var low = 0
        var high = words.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (words[mid].startMs <= ms) low = mid else high = mid - 1
        }
        return low
    }

    fun segmentOfWord(index: Int): Segment {
        var seen = 0
        for (segment in segments) {
            seen += segment.words.size
            if (index < seen) return segment
        }
        return segments.last()
    }
}

/** Groups sentences into paragraphs: a new one after a real pause, or once one has grown long. */
fun paragraphsOf(segments: List<Segment>): List<Paragraph> {
    val out = ArrayList<Paragraph>()
    var current = ArrayList<Segment>()
    var words = 0
    for (segment in segments) {
        if (segment.words.isEmpty()) continue
        val gap = if (current.isEmpty()) 0 else segment.startMs - current.last().endMs
        if (current.isNotEmpty() && (gap > PARAGRAPH_GAP_MS || words >= PARAGRAPH_WORDS)) {
            out += Paragraph(current)
            current = ArrayList()
            words = 0
        }
        current += segment
        words += segment.words.size
    }
    if (current.isNotEmpty()) out += Paragraph(current)
    return out
}

private const val PARAGRAPH_GAP_MS = 2000
private const val PARAGRAPH_WORDS = 70

/**
 * The transcript, linked to the audio: the word being played is highlighted, and tapping any word
 * reports it so the player can jump there.
 *
 * @param partial words of the sentence still being spoken while recording, drawn fainter
 * @param positionMs current playback position, or null when nothing is playing (recording)
 * @param editing when true a tap reports the sentence for editing instead of seeking
 */
@Composable
fun TranscriptView(
    paragraphs: List<Paragraph>,
    modifier: Modifier = Modifier,
    partial: List<Word> = emptyList(),
    positionMs: (() -> Long)? = null,
    editing: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
    onWordTap: (Word) -> Unit = {},
    onEditSegment: (Segment) -> Unit = {},
) {
    val livePartial = remember(partial) { if (partial.isEmpty()) null else Paragraph(listOf(Segment(Long.MAX_VALUE, partial)), live = true) }
    LazyColumn(modifier, state = listState, contentPadding = contentPadding) {
        itemsIndexed(paragraphs, key = { _, p -> p.key }) { _, paragraph ->
            ParagraphBlock(paragraph, positionMs, editing, onWordTap, onEditSegment)
        }
        if (livePartial != null) {
            item(key = "partial") { ParagraphBlock(livePartial, null, false, {}, {}) }
        }
    }
}

@Composable
private fun ParagraphBlock(
    paragraph: Paragraph,
    positionMs: (() -> Long)?,
    editing: Boolean,
    onWordTap: (Word) -> Unit,
    onEditSegment: (Segment) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // Only this paragraph recomposes, and only when playback crosses into a different word.
    val active by remember(paragraph, positionMs) {
        derivedStateOf { positionMs?.let { paragraph.wordAt(it()) } ?: -1 }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val tap = rememberUpdatedState(onWordTap)
    val edit = rememberUpdatedState(onEditSegment)
    val isEditing = rememberUpdatedState(editing)

    val text: AnnotatedString = remember(paragraph, active, colors) {
        buildAnnotatedString {
            if (active < 0) {
                append(paragraph.text)
            } else {
                append(paragraph.text.substring(0, paragraph.offsets[active]))
                withStyle(SpanStyle(color = colors.onPrimaryContainer)) {
                    append(paragraph.text.substring(paragraph.offsets[active], paragraph.offsets[active + 1] - 1))
                }
                append(paragraph.text.substring(paragraph.offsets[active + 1] - 1))
            }
        }
    }
    val highlight = colors.primaryContainer

    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(
            if (paragraph.live) "" else formatClock(paragraph.startMs.toLong()),
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * 1.12f, lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.2f),
            color = if (paragraph.live) colors.onSurfaceVariant.copy(alpha = 0.7f) else colors.onSurface,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val result = layout ?: return@drawBehind
                    if (active < 0 || active >= paragraph.words.size) return@drawBehind
                    val end = (paragraph.offsets[active + 1] - 1).coerceAtMost(result.layoutInput.text.length)
                    val start = paragraph.offsets[active].coerceAtMost(end)
                    val path = result.getPathForRange(start, end)
                    val corner = PathEffect.cornerPathEffect(6.dp.toPx())
                    drawPath(path, highlight, style = Fill)
                    // The stroke pads the box out around the glyphs and rounds its corners.
                    drawPath(path, highlight, style = Stroke(width = 6.dp.toPx(), join = StrokeJoin.Round, pathEffect = corner))
                }
                .pointerInput(paragraph) {
                    if (paragraph.live) return@pointerInput
                    detectTapGestures { position ->
                        val result = layout ?: return@detectTapGestures
                        val index = paragraph.wordAtChar(result.getOffsetForPosition(position))
                        if (index < 0) return@detectTapGestures
                        if (isEditing.value) edit.value(paragraph.segmentOfWord(index)) else tap.value(paragraph.words[index])
                    }
                },
        )
    }
}
