package fm.rizx.player.data.lyrics

import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.RequiresApi
import fm.rizx.player.domain.lyrics.LyricsScript
import fm.rizx.player.domain.model.Lyrics

/**
 * Romanization with no network, using the ICU transliterator built into Android.
 *
 * This exists for the scripts nobody publishes a transcription for. NetEase covers Japanese and Korean
 * well and Musixmatch covers Japanese better, but neither has ever heard of a Russian song: `romalrc`
 * comes back as an empty string for Cyrillic. ICU is bijective there, so it is exactly right.
 *
 * **It refuses far more often than it answers, on purpose:**
 *
 *  - **Never for Japanese.** Because of Han unification, ICU romanizes kanji through Mandarin pinyin —
 *    日本国 becomes `Rì Běn Guó` instead of `Nippon-koku`. Picking Japanese readings needs a
 *    morphological analyzer, not a table. One kana anywhere in the lyric proves the song is Japanese,
 *    and that is the test used here.
 *  - **Only as a last resort.** A transcription a human wrote beats one generated from a table, so this
 *    runs only when every provider came back with nothing.
 *  - **API 29 and up.** `android.icu.text.Transliterator` arrived in Android 10 and this app's floor is
 *    Android 8, so a third of the version range simply doesn't get the option.
 *  - **Only for IDs the device actually has.** Android documents that the available set is unstable
 *    across releases and vendors, so the ID is looked up rather than assumed.
 */
object DeviceRomanizer {

    /** One of these in the lyric means it is Japanese, and that ICU must not touch it. */
    private val JAPANESE = setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)

    /**
     * Scripts ICU transliterates well enough to show someone.
     *
     * Han is here for Chinese, which pinyin is the correct answer for — reachable only once the kana
     * test above has ruled out Japanese.
     */
    private val TRANSFORMS = mapOf(
        Character.UnicodeScript.CYRILLIC to "Cyrillic-Latin",
        Character.UnicodeScript.GREEK to "Greek-Latin",
        Character.UnicodeScript.HANGUL to "Hangul-Latin",
        Character.UnicodeScript.HAN to "Han-Latin",
    )

    /** [lyrics] with a generated pronunciation, or unchanged when this device or script can't have one. */
    fun romanize(lyrics: Lyrics): Lyrics {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return lyrics
        if (lyrics.lines.isEmpty() || lyrics.hasRomanization) return lyrics
        if (LyricsScript.containsAny(lyrics, JAPANESE)) return lyrics

        val id = TRANSFORMS[LyricsScript.dominant(lyrics)] ?: return lyrics
        return runCatching { transliterate(lyrics, id) }.getOrDefault(lyrics)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun transliterate(lyrics: Lyrics, id: String): Lyrics {
        if (!hasTransform(id)) return lyrics
        val transliterator = Transliterator.getInstance(id)
        return lyrics.copy(
            lines = lyrics.lines.map { line ->
                val text = line.text.takeIf { it.isNotBlank() } ?: return@map line
                val latin = transliterator.transliterate(text)
                // A transform that changed nothing has nothing to say — leave the line alone rather than
                // offer the original back as if it were a reading.
                if (latin == text) line else line.copy(romanized = latin)
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun hasTransform(id: String): Boolean {
        val ids = Transliterator.getAvailableIDs()
        while (ids.hasMoreElements()) if (ids.nextElement() == id) return true
        return false
    }
}
