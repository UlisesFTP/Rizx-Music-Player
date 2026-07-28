package fm.rizx.player.domain.lyrics

import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTimelineTest {

    /** "uno dos" with a word each, 500 ms apart. Words carry their own trailing space, as the parsers emit. */
    private val wordTimed = listOf(
        LyricLine(
            timeMs = 1_000,
            text = "uno dos",
            words = listOf(LyricWord(1_000, 1_500, "uno "), LyricWord(1_500, 2_000, "dos")),
            endMs = 2_000,
        ),
        LyricLine(timeMs = 3_000, text = "tres", endMs = 4_000),
    )

    @Test
    fun `before the first line there is no sweep at all`() {
        val timeline = LyricsTimeline.of(wordTimed)

        assertEquals(LyricsSweep.None, timeline.stateAt(0, offsetMs = 0))
    }

    @Test
    fun `the sweep edge advances through the first word`() {
        val timeline = LyricsTimeline.of(wordTimed)

        // "uno dos" is 7 graphemes; the word "uno" covers 0..3 and "dos" covers 4..7. The separating
        // space belongs to neither, which is why the edge jumps it when the second word starts.
        val start = timeline.stateAt(1_000, 0)
        assertEquals(0, start.lineIndex)
        assertEquals(0, start.fromChar)
        assertEquals(0f, start.fraction, 0.001f)

        // A third of the way through "uno" → one letter in, exactly on the boundary.
        val third = timeline.stateAt(1_167, 0)
        assertEquals(1, third.fromChar)
        assertEquals(2, third.toChar)

        // The instant "dos" starts, the edge is at the "d".
        val second = timeline.stateAt(1_500, 0)
        assertEquals(4, second.fromChar)
        assertEquals(5, second.toChar)
        assertEquals(0f, second.fraction, 0.001f)
    }

    @Test
    fun `the fraction interpolates inside a single letter`() {
        val timeline = LyricsTimeline.of(wordTimed)

        // Halfway through "uno" (3 letters) is letter 1, half crossed — the sub-letter position is the
        // whole point: the edge moves continuously, not in whole-letter steps.
        val sweep = timeline.stateAt(1_250, 0)

        assertEquals(1, sweep.fromChar)
        assertEquals(2, sweep.toChar)
        assertTrue("fraction was ${sweep.fraction}", sweep.fraction > 0.4f && sweep.fraction < 0.6f)
    }

    @Test
    fun `a finished line reports its whole text as sung`() {
        val timeline = LyricsTimeline.of(wordTimed)

        val sweep = timeline.stateAt(2_500, 0)

        assertEquals(0, sweep.lineIndex)
        assertEquals(7, sweep.fromChar) // "uno dos".length
        assertEquals(7, sweep.toChar)
    }

    @Test
    fun `the offset is applied exactly once`() {
        val timeline = LyricsTimeline.of(wordTimed)

        // Same instant expressed two ways. Applied twice, the offset version would land 500 ms early.
        val plain = timeline.stateAt(1_250, offsetMs = 0)
        val shifted = timeline.stateAt(1_750, offsetMs = 500)

        assertEquals(plain, shifted)
    }

    @Test
    fun `a line-timed lyric sweeps uniformly across its own duration`() {
        val lines = listOf(LyricLine(timeMs = 0, text = "abcd", endMs = 4_000))
        val timeline = LyricsTimeline.of(lines)

        assertEquals(0, timeline.stateAt(0, 0).fromChar)
        assertEquals(2, timeline.stateAt(2_000, 0).fromChar)
        assertEquals(4, timeline.stateAt(4_000, 0).fromChar)
    }

    @Test
    fun `a blank instrumental line has nothing to sweep but is still active`() {
        val lines = listOf(
            LyricLine(timeMs = 0, text = "hola", endMs = 1_000),
            LyricLine(timeMs = 1_000, text = "", endMs = 5_000),
        )
        val timeline = LyricsTimeline.of(lines)

        val sweep = timeline.stateAt(3_000, 0)

        assertEquals(1, sweep.lineIndex)
        assertEquals(0, sweep.fromChar)
        assertEquals(0, sweep.toChar)
    }

    @Test
    fun `words that do not appear in the line text fall back to the uniform sweep`() {
        // Musixmatch ships the line text separately from its chunks, and they occasionally disagree.
        // Sweeping the wrong characters would be worse than sweeping evenly.
        val lines = listOf(
            LyricLine(
                timeMs = 0,
                text = "totally different text",
                words = listOf(LyricWord(0, 500, "nope "), LyricWord(500, 1_000, "nada")),
                endMs = 1_000,
            ),
        )
        val timeline = LyricsTimeline.of(lines)

        // Uniform: halfway through the line = halfway through its 22 characters.
        val sweep = timeline.stateAt(500, 0)

        assertEquals(11, sweep.fromChar)
    }

    @Test
    fun `an empty lyric produces no sweep`() {
        assertEquals(LyricsSweep.None, LyricsTimeline.of(emptyList()).stateAt(1_000, 0))
        assertEquals(LyricsSweep.None, LyricsTimeline.Empty.stateAt(1_000, 0))
    }

    @Test
    fun `the sweep never splits a grapheme cluster`() {
        // One ZWJ family, swept over a second. Whatever the instant, the edge sits on a cluster boundary.
        val family = "👨‍👩‍👧"
        val lines = listOf(LyricLine(timeMs = 0, text = "a${family}b", endMs = 1_000))
        val timeline = LyricsTimeline.of(lines)
        val boundaries = Graphemes.boundaries("a${family}b").toSet()

        for (t in 0L..1_000L step 50L) {
            val sweep = timeline.stateAt(t, 0)
            assertTrue("edge at $t landed on ${sweep.fromChar}", sweep.fromChar in boundaries)
            assertTrue("edge at $t ended on ${sweep.toChar}", sweep.toChar in boundaries)
        }
    }
}
