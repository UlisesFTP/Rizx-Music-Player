package fm.rizx.player.domain.lyrics

import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.LyricWord
import fm.rizx.player.domain.model.Lyrics
import fm.rizx.player.domain.model.LyricsDisplayMode
import fm.rizx.player.domain.model.LyricsSyncType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Switching reading is the same list of lines with different words in it — and nothing else. */
class LyricsDisplayTest {

    private val song = Lyrics(
        lines = listOf(
            LyricLine(
                timeMs = 1_000,
                text = "沈むように溶けてゆくように",
                words = listOf(LyricWord(1_000, 1_500, "沈む "), LyricWord(1_500, 2_000, "ように")),
                romanized = "shi zu mu yo u ni",
                translated = "Como si nos hundiéramos",
            ),
            // The credit line every source skips: it has neither reading.
            LyricLine(timeMs = 2_000, text = "作詞: Ayase"),
        ),
        sourceName = "NetEase",
    )

    @Test
    fun `the original is returned untouched, identity and all`() {
        assertSame(song, song.inMode(LyricsDisplayMode.ORIGINAL))
    }

    @Test
    fun `pronunciation replaces the words of the lines that have one`() {
        val shown = song.inMode(LyricsDisplayMode.PRONUNCIATION)

        assertEquals("shi zu mu yo u ni", shown.lines[0].text)
    }

    @Test
    fun `a line with no reading keeps its own text instead of going blank`() {
        // A gap here would read as a bug; the original reads as "this line needed no transcribing".
        assertEquals("作詞: Ayase", song.inMode(LyricsDisplayMode.PRONUNCIATION).lines[1].text)
        assertEquals("作詞: Ayase", song.inMode(LyricsDisplayMode.TRANSLATION).lines[1].text)
    }

    @Test
    fun `word timings are dropped, because they describe the original letters`() {
        // The romanization is timed per line and nothing finer, so keeping the spans would sweep the
        // wrong letters. Dropping them makes the view fall back to the uniform sweep.
        val shown = song.inMode(LyricsDisplayMode.PRONUNCIATION)

        assertTrue(shown.lines.all { it.words.isEmpty() })
        assertEquals(LyricsSyncType.LINE_SYNCED, shown.syncType)
        assertEquals(LyricsSyncType.WORD_SYNCED, song.syncType)
    }

    @Test
    fun `timings themselves are untouched, so tap-to-seek keeps working`() {
        val shown = song.inMode(LyricsDisplayMode.TRANSLATION)

        assertEquals(song.lines.map { it.timeMs }, shown.lines.map { it.timeMs })
    }

    @Test
    fun `a reading nobody has changes nothing at all`() {
        val plain = Lyrics(lines = listOf(LyricLine(0, "Yellow")))

        assertSame(plain, plain.inMode(LyricsDisplayMode.PRONUNCIATION))
        assertSame(plain, plain.inMode(LyricsDisplayMode.TRANSLATION))
    }

    @Test
    fun `only the readings that exist are offered`() {
        assertEquals(LyricsDisplayMode.entries, song.availableModes())
        assertEquals(
            listOf(LyricsDisplayMode.ORIGINAL, LyricsDisplayMode.PRONUNCIATION),
            song.copy(lines = song.lines.map { it.copy(translated = null) }).availableModes(),
        )
        assertEquals(
            listOf(LyricsDisplayMode.ORIGINAL),
            Lyrics(lines = listOf(LyricLine(0, "Yellow"))).availableModes(),
        )
    }

    @Test
    fun `a blank reading counts as no reading`() {
        val blank = song.copy(lines = song.lines.map { it.copy(romanized = "   ") })

        assertEquals(listOf(LyricsDisplayMode.ORIGINAL, LyricsDisplayMode.TRANSLATION), blank.availableModes())
        assertEquals("沈むように溶けてゆくように", blank.inMode(LyricsDisplayMode.PRONUNCIATION).lines[0].text)
    }
}
