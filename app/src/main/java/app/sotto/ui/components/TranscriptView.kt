package app.sotto.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import app.sotto.data.SoundSpan
import androidx.compose.ui.Alignment
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.SentimentVerySatisfied
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.sotto.data.Segment
import app.sotto.data.Word
import app.sotto.ui.formatStamp

/**
 * A paragraph on screen: one or more sentences run together. Sentences are short (they end at
 * every pause), so showing each on its own line would read like a list rather than prose.
 */
@Immutable
class Paragraph(
    val segments: List<Segment>,
    val live: Boolean = false,
    /** Where the paragraph before this one ends and the one after begins, or null at either end of the transcript. */
    val previousEndMs: Int? = null,
    val nextStartMs: Int? = null,
) {
    val words: List<Word> = segments.flatMap { it.words }
    val startMs: Int = words.firstOrNull()?.startMs ?: 0
    val endMs: Int = words.lastOrNull()?.endMs ?: 0
    val key: Long = segments.firstOrNull()?.id ?: -1L
    /** Who is speaking in this paragraph, if speakers were told apart. One paragraph is always one speaker. */
    val speaker: Int? = segments.firstOrNull()?.speaker

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

    fun wordStart(index: Int): Int = offsets[index]
    fun wordEnd(index: Int): Int = offsets[index + 1] - 1

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

    /** The part of a selection that falls in this paragraph: which words, and whether it begins or ends here. */
    class SelectedPart(val first: Int, val last: Int, val startsHere: Boolean, val endsHere: Boolean)

    fun selected(startMs: Long, endMs: Long): SelectedPart? {
        val first = words.indexOfFirst { (it.startMs + it.endMs) / 2 >= startMs }
        val last = words.indexOfLast { (it.startMs + it.endMs) / 2 < endMs }
        if (first < 0 || last < first) return null
        return SelectedPart(
            first,
            last,
            startsHere = first > 0 || previousEndMs.let { it == null || startMs >= it },
            endsHere = last < words.lastIndex || nextStartMs.let { it == null || endMs <= it },
        )
    }

    /** Character ranges where [query] occurs, ignoring case. */
    fun find(query: String): List<IntRange> {
        if (query.isBlank()) return emptyList()
        val out = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val at = text.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            out += at until at + query.length
            from = at + query.length
        }
        return out
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
        val speakerChanged = current.isNotEmpty() && segment.speaker != current.last().speaker
        if (current.isNotEmpty() && (gap > PARAGRAPH_GAP_MS || words >= PARAGRAPH_WORDS || speakerChanged)) {
            out += Paragraph(current)
            current = ArrayList()
            words = 0
        }
        current += segment
        words += segment.words.size
    }
    if (current.isNotEmpty()) out += Paragraph(current)
    // Second pass so each paragraph knows its neighbors; a selection uses that to decide whether
    // it starts or ends here or merely passes through.
    return out.mapIndexed { i, p -> Paragraph(p.segments, previousEndMs = out.getOrNull(i - 1)?.endMs, nextStartMs = out.getOrNull(i + 1)?.startMs) }
}

private val GUTTER = 14.dp
private const val PARAGRAPH_GAP_MS = 2000
private const val PARAGRAPH_WORDS = 70

/**
 * The transcript, linked to the audio: the word being played is highlighted, and tapping any word
 * reports it so the player can jump there.
 *
 * @param partial words of the sentence still being spoken while recording, drawn fainter
 * @param positionMs current playback position, or null when nothing is playing (recording)
 * @param editing when true a tap reports the sentence for editing instead of seeking
 * @param query text to mark wherever it occurs (search within the transcript)
 * @param selection a stretch of the recording whose words are shown as selected, with a handle at
 *   each end (crop and remove)
 */
@Composable
fun TranscriptView(
    paragraphs: List<Paragraph>,
    modifier: Modifier = Modifier,
    partial: List<Word> = emptyList(),
    positionMs: (() -> Long)? = null,
    editing: Boolean = false,
    query: String = "",
    selection: (() -> Pair<Long, Long>)? = null,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp - GUTTER, vertical = 20.dp),
    onWordTap: (Word) -> Unit = {},
    onEditSegment: (Segment) -> Unit = {},
    onSelectionDrag: (startEdge: Boolean, word: Word) -> Unit = { _, _ -> },
    sounds: List<SoundSpan> = emptyList(),
    onSoundTap: (SoundSpan) -> Unit = {},
    speakerName: ((Int) -> String)? = null,
    onSpeakerTap: (Int) -> Unit = {},
    onLongPress: (Segment) -> Unit = {},
) {
    val livePartial = remember(partial) { if (partial.isEmpty()) null else Paragraph(listOf(Segment(Long.MAX_VALUE, partial)), live = true) }
    // Each sound marker sits above the first paragraph that starts after it; the rest go at the end.
    val before = remember(paragraphs, sounds) {
        sounds.groupBy { sound -> paragraphs.indexOfFirst { it.startMs >= sound.startMs }.let { if (it < 0) paragraphs.size else it } }
    }
    LazyColumn(modifier, state = listState, contentPadding = contentPadding) {
        itemsIndexed(paragraphs, key = { _, p -> p.key }) { index, paragraph ->
            before[index]?.forEach { SoundMarker(it, onSoundTap) }
            ParagraphBlock(paragraph, positionMs, editing, query, selection, onWordTap, onEditSegment, onSelectionDrag, speakerName, onSpeakerTap, onLongPress)
        }
        before[paragraphs.size]?.let { trailing ->
            item(key = "sounds-end") { trailing.forEach { SoundMarker(it, onSoundTap) } }
        }
        if (livePartial != null) {
            item(key = "partial") { ParagraphBlock(livePartial, null, false, "", null, {}, {}, { _, _ -> }, null, {}, {}) }
        }
    }
}

@Composable
private fun ParagraphBlock(
    paragraph: Paragraph,
    positionMs: (() -> Long)?,
    editing: Boolean,
    query: String,
    selection: (() -> Pair<Long, Long>)?,
    onWordTap: (Word) -> Unit,
    onEditSegment: (Segment) -> Unit,
    onSelectionDrag: (startEdge: Boolean, word: Word) -> Unit,
    speakerName: ((Int) -> String)?,
    onSpeakerTap: (Int) -> Unit,
    onLongPress: (Segment) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val longPress = rememberUpdatedState(onLongPress)
    val dragSelection = rememberUpdatedState(onSelectionDrag)
    val currentSelection = rememberUpdatedState(selection)
    // Only this paragraph recomposes, and only when playback crosses into a different word.
    val active by remember(paragraph, positionMs) {
        derivedStateOf { positionMs?.let { paragraph.wordAt(it()) } ?: -1 }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val tap = rememberUpdatedState(onWordTap)
    val edit = rememberUpdatedState(onEditSegment)
    val isEditing = rememberUpdatedState(editing)

    val text: AnnotatedString = remember(paragraph, active, query, colors) {
        AnnotatedString.Builder(paragraph.text).apply {
            for (match in paragraph.find(query)) {
                // A span background would paint over the active word's box, so that word goes without.
                if (active >= 0 && match.first < paragraph.wordEnd(active) && match.last >= paragraph.wordStart(active)) continue
                addStyle(SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer), match.first, match.last + 1)
            }
            // The box behind the active word is drawn by hand below; here only its text color flips.
            if (active >= 0) addStyle(SpanStyle(color = colors.onPrimary), paragraph.wordStart(active), paragraph.wordEnd(active))
        }.toAnnotatedString()
    }
    val activeBox = colors.primary
    // Opaque, because the box is a fill plus a stroke and a translucent color would show the overlap.
    val selectedBox = colors.outlineVariant
    val handleColor = colors.inverseSurface

    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Row(Modifier.padding(start = GUTTER, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (paragraph.live) "" else formatStamp(paragraph.startMs.toLong()),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = colors.onSurfaceVariant,
            )
            val speaker = paragraph.speaker
            if (speaker != null && speakerName != null) {
                // Each voice keeps one color through the transcript, so a back-and-forth can be followed by eye.
                val tint = listOf(colors.primary, colors.tertiary, colors.secondary, colors.error)[speaker % 4]
                Spacer(Modifier.width(10.dp))
                Text(
                    speakerName(speaker),
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                    modifier = Modifier.clip(CircleShape).clickable { onSpeakerTap(speaker) }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * 1.18f, lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.3f),
            color = if (paragraph.live) colors.onSurfaceVariant.copy(alpha = 0.7f) else colors.onSurface,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val result = layout ?: return@drawBehind
                    val length = result.layoutInput.text.length
                    // Everything below is in the text's own coordinates, which start past the gutter.
                    translate(left = GUTTER.toPx()) {
                        val corner = PathEffect.cornerPathEffect(4.dp.toPx())
                        fun box(from: Int, to: Int, color: Color) {
                            if (to <= from || to > length) return
                            val path = result.getPathForRange(from, to)
                            drawPath(path, color, style = Fill)
                            // The stroke pads the box out around the glyphs and rounds its corners.
                            drawPath(path, color, style = Stroke(width = 5.dp.toPx(), join = StrokeJoin.Round, pathEffect = corner))
                        }

                        selection?.invoke()?.let { (start, end) ->
                            val part = paragraph.selected(start, end) ?: return@let
                            val from = paragraph.wordStart(part.first)
                            val to = paragraph.wordEnd(part.last)
                            box(from, to, selectedBox)
                            // A grip where the selection begins and ends, if that is in this paragraph.
                            val grip = Size(8.dp.toPx(), 0f)
                            if (part.startsHere) {
                                val at = result.getBoundingBox(from.coerceAtMost(length - 1))
                                drawRoundRect(handleColor, Offset(at.left - 9.dp.toPx(), at.top - 2.dp.toPx()), grip.copy(height = at.height + 4.dp.toPx()), CornerRadius(4.dp.toPx()))
                            }
                            if (part.endsHere) {
                                val at = result.getBoundingBox((to - 1).coerceIn(0, length - 1))
                                drawRoundRect(handleColor, Offset(at.right + 1.dp.toPx(), at.top - 2.dp.toPx()), grip.copy(height = at.height + 4.dp.toPx()), CornerRadius(4.dp.toPx()))
                            }
                        }
                        if (active in paragraph.words.indices) box(paragraph.wordStart(active), paragraph.wordEnd(active), activeBox)
                    }
                }
                // Dragging a grip moves that end of the selection word by word. Only a touch that
                // lands on a grip is claimed, so anywhere else still scrolls the transcript.
                .pointerInput(paragraph, selection != null) {
                    if (selection == null) return@pointerInput
                    val reach = 30.dp.toPx()
                    val gutter = Offset(GUTTER.toPx(), 0f)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val result = layout ?: return@awaitEachGesture
                        val (start, end) = currentSelection.value?.invoke() ?: return@awaitEachGesture
                        val part = paragraph.selected(start, end) ?: return@awaitEachGesture
                        val length = result.layoutInput.text.length
                        val startBox = result.getBoundingBox(paragraph.wordStart(part.first).coerceAtMost(length - 1))
                        val endBox = result.getBoundingBox((paragraph.wordEnd(part.last) - 1).coerceIn(0, length - 1))
                        val toStart = (down.position - gutter - Offset(startBox.left, startBox.center.y)).getDistance()
                        val toEnd = (down.position - gutter - Offset(endBox.right, endBox.center.y)).getDistance()
                        val startEdge = when {
                            part.startsHere && toStart < reach && (toStart <= toEnd || !part.endsHere) -> true
                            part.endsHere && toEnd < reach -> false
                            else -> return@awaitEachGesture
                        }
                        // A finger never lands exactly on the grip. Remember by how much it missed
                        // and keep that offset for the whole drag, so the selection does not jump
                        // the moment it is touched.
                        val grip = if (startEdge) Offset(startBox.left, startBox.center.y) else Offset(endBox.right, endBox.center.y)
                        val miss = down.position - gutter - grip
                        down.consume()
                        var last = -1
                        drag(down.id) { change ->
                            change.consume()
                            val at = change.position - gutter - miss
                            val index = paragraph.wordAtChar(result.getOffsetForPosition(at))
                            if (index < 0) return@drag
                            // The edge goes to whichever side of this word is nearer, so it only
                            // moves once the finger is past the middle of a word, not on first contact.
                            val left = result.getBoundingBox(paragraph.wordStart(index).coerceAtMost(length - 1)).left
                            val right = result.getBoundingBox((paragraph.wordEnd(index) - 1).coerceIn(0, length - 1)).right
                            val pastMiddle = at.x > (left + right) / 2f
                            val target = when {
                                startEdge -> if (pastMiddle) index + 1 else index
                                else -> if (pastMiddle) index else index - 1
                            }
                            if (target == last || target !in paragraph.words.indices) return@drag
                            last = target
                            dragSelection.value(startEdge, paragraph.words[target])
                        }
                    }
                }
                .pointerInput(paragraph) {
                    if (paragraph.live) return@pointerInput
                    fun wordAt(position: Offset): Int = layout?.let { paragraph.wordAtChar(it.getOffsetForPosition(position - Offset(GUTTER.toPx(), 0f))) } ?: -1
                    detectTapGestures(
                        // Holding a sentence is the quick way to correct it, without going through the menu.
                        onLongPress = { position -> wordAt(position).takeIf { it >= 0 }?.let { longPress.value(paragraph.segmentOfWord(it)) } },
                    ) { position ->
                        val index = wordAt(position)
                        if (index < 0) return@detectTapGestures
                        if (isEditing.value) edit.value(paragraph.segmentOfWord(index)) else tap.value(paragraph.words[index])
                    }
                }
                // Inside the gesture and drawing modifiers, so the selection grips, which hang
                // just outside the text, are still within reach of a finger.
                .padding(horizontal = GUTTER),
        )
    }
}

/** "Music 00:12", in the transcript where the music was. Tapping it plays from there. */
@Composable
private fun SoundMarker(sound: SoundSpan, onTap: (SoundSpan) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = { onTap(sound) },
        shape = CircleShape,
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
        modifier = Modifier.padding(start = GUTTER, bottom = 20.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (sound.kind) {
                    SoundSpan.MUSIC -> Icons.Rounded.MusicNote
                    SoundSpan.LAUGHTER -> Icons.Rounded.SentimentVerySatisfied
                    else -> Icons.Rounded.GraphicEq
                },
                null,
                Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("${sound.kind}  ${formatStamp(sound.startMs.toLong())}", style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
        }
    }
}
