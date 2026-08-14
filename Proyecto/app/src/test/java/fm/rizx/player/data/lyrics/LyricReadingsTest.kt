package fm.rizx.player.data.lyrics

import fm.rizx.player.domain.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stitching a lyric back together with the separate document its pronunciation arrives in. */
class LyricReadingsTest {

    // NetEase's own shape: `lrc` opens with a credit line that `romalrc` does not have.
    private val lrc = listOf(
        LyricLine(timeMs = 0, text = "作詞: Ayase"),
        LyricLine(timeMs = 1_430, text = "沈むように溶けてゆくように"),
        LyricLine(timeMs = 8_831, text = "二人だけの空が広がる夜に"),
    )

    private val romalrc = listOf(
        LyricLine(timeMs = 1_430, text = "shi zu mu yo u ni"),
        LyricLine(timeMs = 8_831, text = "fu ta ri da ke no"),
    )

    @Test
    fun `readings are matched by timestamp, so a missing credit line costs nothing`() {
        // Walking both lists in step would put "shi zu mu" on the credit line and drift from there on.
        val merged = lrc.withReadings(romanized = romalrc)

        assertNull(merged[0].romanized)
        assertEquals("shi zu mu yo u ni", merged[1].romanized)
        assertEquals("fu ta ri da ke no", merged[2].romanized)
    }

    @Test
    fun `nothing to attach means the very same list back`() {
        assertSame(lrc, lrc.withReadings())
    }

    @Test
    fun `the original text and timings survive the merge`() {
        val merged = lrc.withReadings(romanized = romalrc)

        assertEquals(lrc.map { it.text }, merged.map { it.text })
        assertEquals(lrc.map { it.timeMs }, merged.map { it.timeMs })
    }

    @Test
    fun `a timestamp with no counterpart simply gets no reading`() {
        val merged = lrc.withReadings(romanized = listOf(LyricLine(timeMs = 99_999, text = "orphan")))

        assertTrue(merged.all { it.romanized == null })
    }

    // ---- joined by text, for the source that ships no timings ----

    @Test
    fun `punctuation and spacing drift between two transcriptions does not break the join`() {
        // Musixmatch keys its crowd translations by its *own* copy of the line, and the line on screen
        // may have come from NetEase. Only the letters are compared.
        val crowd = mapOf(
            "「さよなら」だけだった" to "Solo fue un simple \"adiós\"",
            "沈むように 溶けてゆくように" to "Como si nos hundiéramos",
        )
        val merged = lrc.withReadingsByText(translated = crowd)

        assertEquals("Como si nos hundiéramos", merged[1].translated)
    }

    @Test
    fun `a genuinely different line is still not matched`() {
        val merged = lrc.withReadingsByText(translated = mapOf("something else entirely" to "nope"))

        assertTrue(merged.all { it.translated == null })
    }

    @Test
    fun `case and punctuation-only differences agree`() {
        val lines = listOf(LyricLine(timeMs = 0, text = "Don't stop me now!"))

        assertEquals(
            "No me pares ahora",
            lines.withReadingsByText(translated = mapOf("Dont stop me, now" to "No me pares ahora"))[0].translated,
        )
    }
}
