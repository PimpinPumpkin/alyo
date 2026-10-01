package app.sotto

import app.sotto.audio.Adts
import app.sotto.audio.Decimator
import app.sotto.audio.Peaks
import app.sotto.audio.Span
import app.sotto.audio.slice
import app.sotto.audio.totalMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AudioTest {
    @Test
    fun `adts header round-trips the frame length`() {
        val header = ByteArray(Adts.HEADER_SIZE)
        for (payload in listOf(1, 187, 512, 2047, 6000)) {
            Adts.fillHeader(header, payload)
            assertEquals(payload + Adts.HEADER_SIZE, Adts.frameLength(header))
        }
        assertEquals(-1, Adts.frameLength(ByteArray(Adts.HEADER_SIZE)))
    }

    @Test
    fun `decimator yields exactly a third of the samples across uneven blocks`() {
        val decimator = Decimator()
        var out = 0
        for (size in listOf(1920, 1000, 7, 1, 1919, 3)) out += decimator.process(ShortArray(size), size).size
        assertEquals((1920 + 1000 + 7 + 1 + 1919 + 3) / 3, out)
    }

    @Test
    fun `decimator passes speech frequencies and removes what would alias`() {
        fun level(hz: Double): Float {
            val input = ShortArray(48_000) { (sin(2 * PI * hz * it / 48_000) * 20_000).toInt().toShort() }
            val out = Decimator().process(input, input.size)
            // RMS rather than the largest sample: at 4 kHz there are only four samples per cycle,
            // and none of them need land on the crest.
            val settled = out.drop(200)
            val rms = sqrt(settled.sumOf { (it * it).toDouble() } / settled.size)
            return (rms * sqrt(2.0) / (20_000 / 32768.0)).toFloat()
        }
        assertTrue("1 kHz should pass", level(1_000.0) > 0.95f)
        assertTrue("4 kHz should pass", level(4_000.0) > 0.9f)
        assertTrue("12 kHz must be gone, or it folds onto 4 kHz", level(12_000.0) < 0.02f)
    }

    @Test
    fun `peaks slice keeps only the bars of the kept audio`() {
        val peaks = Peaks()
        // 100 bars, 20 ms each = 2 s, getting steadily louder.
        for (bar in 0 until 100) peaks.add(ShortArray(960) { (bar * 300).toShort() }, 960)
        assertEquals(100, peaks.size)
        val sliced = peaks.slice(listOf(Span(0, 400), Span(1000, 1200)))
        assertEquals(20 + 10, sliced.size)
        assertEquals(peaks.level(50), sliced.level(20))
    }
}

class SpanTest {
    @Test
    fun `a selection on an edited recording maps back to the original audio`() {
        // The recording has already had 2s..5s cut out of it.
        val kept = listOf(Span(0, 2000), Span(5000, 9000))
        assertEquals(6000L, kept.totalMs)
        // Selecting 1s..3s of the result straddles the join.
        assertEquals(listOf(Span(1000, 2000), Span(5000, 6000)), kept.slice(1000, 3000))
        assertEquals(listOf(Span(5500, 9000)), kept.slice(2500, 6000))
        assertEquals(kept, kept.slice(0, 6000))
        assertTrue(kept.slice(3000, 3000).isEmpty())
    }
}
