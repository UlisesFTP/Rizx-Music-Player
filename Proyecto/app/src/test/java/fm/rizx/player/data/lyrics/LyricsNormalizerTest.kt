package fm.rizx.player.data.lyrics

import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.LyricWord
import fm.rizx.player.domain.model.Lyrics
import fm.rizx.player.domain.model.LyricsSyncType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsNormalizerTest {

    private fun worded(text: String, at: Long, end: Long) = LyricLine(
        timeMs = at,
        text = text,
        words = listOf(LyricWord(at, end, text)),
        endMs = end,
    )

    @Test
    fun `a line inherits the next line's start as its end`() {
        val lyrics = Lyrics(
            lines = listOf(
                LyricLine(timeMs = 0, text = "una"),
                LyricLine(timeMs = 4_000, text = "dos"),
            ),
        )

        val lines = LyricsNormalizer.normalize(lyrics).lines

        assertEquals(4_000L, lines[0].endMs)
    }

    @Test
    fun `the last line gets a tail so it eventually stops being sung`() {
        val lyrics = Lyrics(lines = listOf(LyricLine(timeMs = 10_000, text = "final")))

        val lines = LyricsNormalizer.normalize(lyrics).lines

        assertTrue("last line never ends", lines[0].endMs > lines[0].timeMs)
    }

    @Test
    fun `an end the format actually supplied is kept`() {
        val lyrics = Lyrics(
            lines = listOf(
                LyricLine(timeMs = 0, text = "una", endMs = 1_500),
                LyricLine(timeMs = 4_000, text = "dos"),
            ),
        )

        // The gap between 1.5s and 4s is a pause: the line stops being sung, it doesn't run on.
        assertEquals(1_500L, LyricsNormalizer.normalize(lyrics).lines[0].endMs)
    }

    @Test
    fun `lines arrive sorted whatever order the provider used`() {
        val lyrics = Lyrics(
            lines = listOf(
                LyricLine(timeMs = 8_000, text = "tres"),
                LyricLine(timeMs = 2_000, text = "una"),
                LyricLine(timeMs = 5_000, text = "dos"),
            ),
        )

        val lines = LyricsNormalizer.normalize(lyrics).lines

        assertEquals(listOf(2_000L, 5_000L, 8_000L), lines.map { it.timeMs })
    }

    @Test
    fun `a word ending before it starts is repaired, not trusted`() {
        val lyrics = Lyrics(
            lines = listOf(
                LyricLine(
                    timeMs = 0,
                    text = "uno dos",
                    words = listOf(LyricWord(1_000, 400, "uno "), LyricWord(1_500, 2_000, "dos")),
                    endMs = 2_000,
                ),
            ),
        )

        val words = LyricsNormalizer.normalize(lyrics).lines[0].words

        assertTrue(words.all { it.endMs >= it.startMs })
    }

    @Test
    fun `words with no duration at all are dropped, so the lyric is honestly line-timed`() {
        // Some sources repeat the line stamp on every word. There is nothing to sweep across there, and
        // calling it word-synced would promise a precision the data doesn't have.
        val lyrics = Lyrics(
            lines = listOf(
                LyricLine(
                    timeMs = 1_000,
                    text = "uno dos",
                    words = listOf(LyricWord(1_000, 1_000, "uno "), LyricWord(1_000, 1_000, "dos")),
                    endMs = 2_000,
                ),
            ),
        )

        val normalized = LyricsNormalizer.normalize(lyrics)

        assertEquals(LyricsSyncType.LINE_SYNCED, normalized.syncType)
        assertEquals(emptyList<LyricWord>(), normalized.lines[0].words)
    }

    @Test
    fun `a mostly untimed lyric is demoted to line-timed rather than flickering between renderers`() {
        // One enhanced-LRC line among nine plain ones is debris, not a karaoke file.
        val lines = buildList {
            add(worded("timed", 0, 500))
            repeat(9) { add(LyricLine(timeMs = (it + 1) * 1_000L, text = "plain $it")) }
        }

        val normalized = LyricsNormalizer.normalize(Lyrics(lines = lines))

        assertEquals(LyricsSyncType.LINE_SYNCED, normalized.syncType)
        assertTrue(normalized.lines.all { it.words.isEmpty() })
    }

    @Test
    fun `blank instrumental lines do not count against word coverage`() {
        val lines = listOf(
            worded("una", 0, 900),
            LyricLine(timeMs = 1_000, text = ""),
            LyricLine(timeMs = 5_000, text = ""),
            worded("dos", 8_000, 8_900),
        )

        val normalized = LyricsNormalizer.normalize(Lyrics(lines = lines))

        assertEquals(LyricsSyncType.WORD_SYNCED, normalized.syncType)
    }

    @Test
    fun `prose is left exactly as it came`() {
        val lyrics = Lyrics(plain = "Look at the stars", sourceName = "lyrics.ovh")

        val normalized = LyricsNormalizer.normalize(lyrics)

        assertSame(lyrics, normalized)
        assertEquals(LyricsSyncType.PLAIN, normalized.syncType)
    }

    @Test
    fun `normalising twice changes nothing`() {
        val lyrics = Lyrics(
            lines = listOf(
                LyricLine(timeMs = 8_000, text = "tres"),
                worded("una", 2_000, 2_500),
                LyricLine(timeMs = 5_000, text = "dos"),
            ),
        )

        val once = LyricsNormalizer.normalize(lyrics)
        val twice = LyricsNormalizer.normalize(once)

        assertEquals(once, twice)
        // Idempotent enough to return the very same object, so a cache hit costs nothing.
        assertSame(once, twice)
    }
}
