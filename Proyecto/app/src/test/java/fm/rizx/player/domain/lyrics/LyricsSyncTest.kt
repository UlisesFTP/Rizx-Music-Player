package fm.rizx.player.domain.lyrics

import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsSyncTest {

    private fun lines(vararg at: Long) = at.mapIndexed { i, t -> LyricLine(timeMs = t, text = "line $i") }

    @Test
    fun `active index tracks the line being sung`() {
        val lines = lines(0, 10_000, 20_000)

        assertEquals(-1, lines.activeIndexAt(-1L))
        assertEquals(0, lines.activeIndexAt(0L))
        assertEquals(0, lines.activeIndexAt(9_999L))
        assertEquals(1, lines.activeIndexAt(10_000L))
        assertEquals(2, lines.activeIndexAt(600_000L))
    }

    @Test
    fun `a positive offset delays the lyrics against the audio`() {
        val lines = lines(0, 10_000)

        // At 10s with the words pushed 2s later, line two has not started yet.
        assertEquals(0, lines.activeIndexAt(10_000L, offsetMs = 2_000L))
        assertEquals(1, lines.activeIndexAt(12_000L, offsetMs = 2_000L))
    }

    @Test
    fun `an empty lyric has no active line`() {
        assertEquals(-1, emptyList<LyricLine>().activeIndexAt(5_000L))
    }

    @Test
    fun `a single line is active from its own timestamp onwards`() {
        val lines = lines(5_000)

        assertEquals(-1, lines.activeIndexAt(4_999L))
        assertEquals(0, lines.activeIndexAt(5_000L))
        assertEquals(0, lines.activeIndexAt(Long.MAX_VALUE / 2))
    }

    @Test
    fun `lines sharing a timestamp resolve to the last of them`() {
        // A repeated chorus is emitted once per stamp; the binary search must land on the same one the
        // old linear scan did, or a re-sung line would light up its first copy far up the list.
        val lines = lines(0, 10_000, 10_000, 20_000)

        assertEquals(2, lines.activeIndexAt(10_000L))
        assertEquals(2, lines.activeIndexAt(19_999L))
    }

    @Test
    fun `seeking backwards moves the active line back`() {
        val lines = lines(0, 10_000, 20_000, 30_000)

        assertEquals(3, lines.activeIndexAt(31_000L))
        assertEquals(1, lines.activeIndexAt(11_000L))
        assertEquals(-1, lines.activeIndexAt(-500L))
    }

    @Test
    fun `word progress runs from zero to one across the word`() {
        val word = LyricWord(startMs = 1_000, endMs = 2_000, text = "hola")

        assertEquals(0f, word.progressAt(500), 0.001f)
        assertEquals(0f, word.progressAt(1_000), 0.001f)
        assertEquals(0.5f, word.progressAt(1_500), 0.001f)
        assertEquals(1f, word.progressAt(2_000), 0.001f)
        assertEquals(1f, word.progressAt(9_999), 0.001f)
    }

    @Test
    fun `a zero-length word never divides by zero`() {
        val word = LyricWord(startMs = 1_000, endMs = 1_000, text = "x")

        assertEquals(0f, word.progressAt(999), 0.001f)
        assertEquals(1f, word.progressAt(1_000), 0.001f)
    }

    @Test
    fun `uniform progress spreads a line-timed line across its own duration`() {
        val line = LyricLine(timeMs = 10_000, text = "una frase", endMs = 14_000)

        assertEquals(0f, line.uniformProgressAt(9_000), 0.001f)
        assertEquals(0.25f, line.uniformProgressAt(11_000), 0.001f)
        assertEquals(1f, line.uniformProgressAt(14_000), 0.001f)
        assertEquals(1f, line.uniformProgressAt(99_000), 0.001f)
    }

    @Test
    fun `a line with no end is treated as finished once it starts`() {
        // `endMs` 0 only survives normalisation for a lyric that never went through it; the sweep must
        // still not produce a negative or infinite fraction.
        val line = LyricLine(timeMs = 10_000, text = "sin fin")

        assertEquals(0f, line.uniformProgressAt(9_999), 0.001f)
        assertEquals(1f, line.uniformProgressAt(10_000), 0.001f)
    }

    @Test
    fun `word index is minus one before the first word starts`() {
        val line = LyricLine(
            timeMs = 1_000,
            text = "uno dos",
            words = listOf(LyricWord(1_200, 1_600, "uno "), LyricWord(1_600, 2_000, "dos")),
        )

        assertEquals(-1, line.wordIndexAt(1_000))
        assertEquals(0, line.wordIndexAt(1_200))
        assertEquals(1, line.wordIndexAt(1_600))
        assertEquals(1, line.wordIndexAt(50_000))
    }
}
