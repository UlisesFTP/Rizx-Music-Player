package fm.rizx.player.domain.lyrics

import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which songs get offered a reading switch at all.
 *
 * This is the gate in front of a rate-limited endpoint, so both mistakes cost something real: too eager
 * and the translation budget is spent on English songs, too strict and the songs that need it most —
 * K-pop, which is half English by line count — never get the offer.
 */
class LyricsScriptTest {

    private fun lyrics(vararg lines: String) =
        Lyrics(lines = lines.mapIndexed { i, text -> LyricLine(i * 1_000L, text) })

    @Test
    fun `an english song is not foreign`() {
        assertFalse(LyricsScript.isForeign(lyrics("Look at the stars", "Look how they shine for you")))
    }

    @Test
    fun `japanese is foreign`() {
        assertTrue(LyricsScript.isForeign(lyrics("沈むように溶けてゆくように", "二人だけの空が広がる夜に")))
    }

    @Test
    fun `a k-pop lyric with an english chorus is still foreign`() {
        // The case that decides the threshold: whole lines are in English, and the song is not.
        val dna = lyrics(
            "첫눈에 널 알아보게 됐어",
            "서를 불러왔던 것처럼",
            "I want it this love, I want it real love",
            "난 너에게 나를 맡길게",
        )

        assertTrue(LyricsScript.isForeign(dna))
    }

    @Test
    fun `one borrowed word does not make an english song foreign`() {
        val song = lyrics(
            "I was walking through the city streets",
            "and a man walks up to me",
            "he says こんにちは",
            "and I keep on walking down the avenue tonight",
        )

        assertFalse(LyricsScript.isForeign(song))
    }

    @Test
    fun `an empty lyric is not foreign`() {
        assertFalse(LyricsScript.isForeign(Lyrics()))
        assertFalse(LyricsScript.isForeign(lyrics("", "  ", "123 !!!")))
    }

    @Test
    fun `the dominant script ignores the latin that is mixed in`() {
        assertEquals(
            Character.UnicodeScript.HANGUL,
            LyricsScript.dominant(lyrics("첫눈에 널 알아보게 됐어", "I want it real love")),
        )
        assertEquals(
            Character.UnicodeScript.CYRILLIC,
            LyricsScript.dominant(lyrics("Тёплое место, но улицы ждут")),
        )
    }

    @Test
    fun `a latin lyric has no dominant foreign script`() {
        assertEquals(null, LyricsScript.dominant(lyrics("Look at the stars")))
    }

    @Test
    fun `one kana anywhere proves the song is japanese`() {
        // This is what keeps ICU away from kanji: alone, they would be romanized as Mandarin pinyin.
        val kana = setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)

        assertTrue(LyricsScript.containsAny(lyrics("残酷な天使のテーゼ"), kana))
        assertFalse(LyricsScript.containsAny(lyrics("告白气球"), kana))
        assertFalse(LyricsScript.containsAny(lyrics("첫눈에 널"), kana))
    }
}
