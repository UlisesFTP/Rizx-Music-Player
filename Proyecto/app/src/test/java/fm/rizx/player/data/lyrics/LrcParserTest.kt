package fm.rizx.player.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses the shape LRCLIB actually returns`() {
        val lines = LrcParser.parse(
            """
            [00:31.48] Like the legend of the phoenix
            [00:35.40] All ends with beginnings
            """.trimIndent(),
        )

        assertEquals(2, lines.size)
        assertEquals(31_480L, lines[0].timeMs)
        assertEquals("Like the legend of the phoenix", lines[0].text)
        assertEquals(35_400L, lines[1].timeMs)
    }

    @Test
    fun `fraction width scales, it is not always hundredths`() {
        // The same instant written three ways. Reading the digits as a fixed field would put the
        // 1- and 3-digit forms 10x out.
        val lines = LrcParser.parse("[01:02.5] a\n[01:02.50] b\n[01:02.500] c")

        assertEquals(listOf(62_500L, 62_500L, 62_500L), lines.map { it.timeMs })
    }

    @Test
    fun `a timestamp with no fraction lands on the second`() {
        assertEquals(listOf(62_000L), LrcParser.parse("[01:02] a").map { it.timeMs })
    }

    @Test
    fun `a colon before the fraction is accepted too`() {
        assertEquals(listOf(62_500L), LrcParser.parse("[01:02:50] a").map { it.timeMs })
    }

    @Test
    fun `one line with several timestamps becomes several lines`() {
        val lines = LrcParser.parse("[00:10.00][01:20.00] chorus")

        assertEquals(2, lines.size)
        assertTrue(lines.all { it.text == "chorus" })
        assertEquals(listOf(10_000L, 80_000L), lines.map { it.timeMs })
    }

    @Test
    fun `output is sorted by time even when the file is not`() {
        val lines = LrcParser.parse("[02:00.00] second\n[00:30.00] first")

        assertEquals(listOf("first", "second"), lines.map { it.text })
    }

    @Test
    fun `timestamped blank lines survive as instrumental gaps`() {
        // Dropping these would leave the last sung line highlighted through a whole solo.
        val lines = LrcParser.parse("[00:10.00] words\n[00:14.00] \n[00:20.00] more")

        assertEquals(3, lines.size)
        assertEquals("", lines[1].text)
    }

    @Test
    fun `metadata tags are skipped but offset is applied`() {
        val lines = LrcParser.parse(
            """
            [ar:Daft Punk]
            [ti:Get Lucky]
            [length:04:08]
            [offset:+500]
            [00:10.00] words
            """.trimIndent(),
        )

        assertEquals(1, lines.size)
        assertEquals(10_500L, lines[0].timeMs)
    }

    @Test
    fun `a negative offset cannot push a line before zero`() {
        val lines = LrcParser.parse("[offset:-5000]\n[00:01.00] early")

        assertEquals(0L, lines[0].timeMs)
    }

    @Test
    fun `brackets inside the words do not truncate the line`() {
        val lines = LrcParser.parse("[00:10.00] hello [laughs] world")

        assertEquals("hello [laughs] world", lines[0].text)
    }

    @Test
    fun `plain prose parses to nothing, which is how the caller detects unsynced text`() {
        assertTrue(LrcParser.parse("Look at the stars\nHow they shine for you").isEmpty())
        assertTrue(LrcParser.parse(null).isEmpty())
        assertTrue(LrcParser.parse("   ").isEmpty())
    }

    @Test
    fun `active index tracks the line being sung`() {
        val lines = LrcParser.parse("[00:00.00] one\n[00:10.00] two\n[00:20.00] three")

        assertEquals(-1, lines.activeIndexAt(-1L))
        assertEquals(0, lines.activeIndexAt(0L))
        assertEquals(0, lines.activeIndexAt(9_999L))
        assertEquals(1, lines.activeIndexAt(10_000L))
        assertEquals(2, lines.activeIndexAt(600_000L))
    }

    @Test
    fun `a positive offset delays the lyrics against the audio`() {
        val lines = LrcParser.parse("[00:00.00] one\n[00:10.00] two")

        // At 10s with the words pushed 2s later, line two has not started yet.
        assertEquals(0, lines.activeIndexAt(10_000L, offsetMs = 2_000L))
        assertEquals(1, lines.activeIndexAt(12_000L, offsetMs = 2_000L))
    }

    @Test
    fun `an empty lyric has no active line`() {
        assertEquals(-1, emptyList<fm.rizx.player.domain.model.LyricLine>().activeIndexAt(5_000L))
    }
}
