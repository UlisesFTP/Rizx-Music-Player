package fm.rizx.player.data.lyrics

import fm.rizx.player.domain.model.Lyrics
import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.LyricWord
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SungWordCountTest {

    private val line = LyricLine(
        timeMs = 1_000,
        text = "uno dos tres",
        words = listOf(
            LyricWord(1_000, 1_500, "uno "),
            LyricWord(1_500, 2_000, "dos "),
            LyricWord(2_000, 2_500, "tres"),
        ),
    )

    @Test
    fun `counts the words already sung at each boundary`() {
        assertEquals(0, line.sungWordCountAt(999))
        assertEquals(1, line.sungWordCountAt(1_000)) // a word counts the instant it starts
        assertEquals(1, line.sungWordCountAt(1_499))
        assertEquals(2, line.sungWordCountAt(1_500))
        assertEquals(3, line.sungWordCountAt(9_999)) // past the end, the whole line is lit
    }

    @Test
    fun `the offset shifts the words with the line, not against it`() {
        // +500 ms means "these words arrive later", so at 1_000 nothing has been sung yet.
        assertEquals(0, line.sungWordCountAt(1_000, offsetMs = 500))
        assertEquals(1, line.sungWordCountAt(1_500, offsetMs = 500))
    }

    @Test
    fun `a line without word timings reports none, so the UI lights the whole line`() {
        assertEquals(0, LyricLine(0, "sin palabras").sungWordCountAt(10_000))
    }

    @Test
    fun `lyrics cached before word timings existed still decode`() {
        // Exactly the shape LyricsStore wrote before this feature: no "words" key anywhere.
        val old = """{"plain":null,"lines":[{"timeMs":1000,"text":"hola"}],"sourceName":"LRCLIB"}"""

        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<Lyrics>(old)

        assertEquals(1, decoded.lines.size)
        assertEquals("hola", decoded.lines[0].text)
        assertEquals(emptyList<LyricWord>(), decoded.lines[0].words)
        assertEquals(false, decoded.isWordSynced)
    }
}
