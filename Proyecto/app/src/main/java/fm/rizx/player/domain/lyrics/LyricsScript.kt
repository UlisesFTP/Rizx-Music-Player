package fm.rizx.player.domain.lyrics

import fm.rizx.player.domain.model.Lyrics

/**
 * What alphabet a lyric is written in.
 *
 * This is what decides whether the reading switch is worth showing at all. Offering "pronunciation" on
 * an English song is a button that can only disappoint, and — because the translation has to be fetched
 * from an endpoint that rate-limits after about ten calls — offering it everywhere would spend the
 * budget on songs nobody needed it for.
 *
 * `java.lang.Character.UnicodeScript` is plain JVM, so this stays in `domain` and is unit-testable.
 */
object LyricsScript {

    /**
     * A lyric counts as foreign-script when this share of its letters are outside the Latin alphabet.
     *
     * Not "any", and not "most". K-pop and J-pop lyrics are full of English hooks — a chorus can be
     * entirely Latin — while an English song borrowing one Japanese word is not a foreign-script lyric.
     * A fifth of the letters separates the two cleanly.
     */
    private const val FOREIGN_SHARE = 0.20

    /** How many characters are worth reading before the answer stops changing. */
    private const val SAMPLE = 4_000

    /** True when enough of the lyric is written in something other than the Latin alphabet. */
    fun isForeign(lyrics: Lyrics): Boolean {
        var letters = 0
        var foreign = 0
        forEachLetter(lyrics) { script ->
            letters++
            if (script != Character.UnicodeScript.LATIN) foreign++
        }
        return letters > 0 && foreign.toDouble() / letters >= FOREIGN_SHARE
    }

    /** The non-Latin script most of the lyric is written in, or `null` when it is Latin (or empty). */
    fun dominant(lyrics: Lyrics): Character.UnicodeScript? {
        val counts = HashMap<Character.UnicodeScript, Int>()
        forEachLetter(lyrics) { script ->
            if (script != Character.UnicodeScript.LATIN) counts[script] = (counts[script] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key
    }

    /** True when any of [scripts] appears at all — one kana is enough to prove a lyric is Japanese. */
    fun containsAny(lyrics: Lyrics, scripts: Set<Character.UnicodeScript>): Boolean {
        var found = false
        forEachLetter(lyrics) { script -> if (script in scripts) found = true }
        return found
    }

    private inline fun forEachLetter(lyrics: Lyrics, block: (Character.UnicodeScript) -> Unit) {
        var seen = 0
        for (line in lyrics.lines) {
            for (ch in line.text) {
                if (!ch.isLetter()) continue
                if (seen++ >= SAMPLE) return
                val script = runCatching { Character.UnicodeScript.of(ch.code) }.getOrNull() ?: continue
                block(script)
            }
        }
        if (lyrics.lines.isNotEmpty()) return
        for (ch in lyrics.plain.orEmpty()) {
            if (!ch.isLetter()) continue
            if (seen++ >= SAMPLE) return
            val script = runCatching { Character.UnicodeScript.of(ch.code) }.getOrNull() ?: continue
            block(script)
        }
    }
}
