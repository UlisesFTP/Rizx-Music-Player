package fm.rizx.player.data.lyrics

import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.LyricWord
import fm.rizx.player.domain.model.Lyrics

/**
 * The single gate every lyric passes through between a provider and the rest of the app.
 *
 * Four formats reach us with four different ideas of what a timing is, and the karaoke renderer can't
 * defend itself against any of them: it needs lines in order, a line that *ends*, and word timings that
 * actually describe a sweep. Normalising once here is what lets `Lyrics.syncType` be a derived property
 * the UI can trust rather than a guess, and what keeps the four parsers free of repair logic.
 *
 * It is **idempotent** — normalising an already-normalised lyric changes nothing — because it also runs
 * on cache reads, where entries written before line ends existed still need one.
 */
object LyricsNormalizer {

    /**
     * How much of a lyric has to be genuinely word-timed before the whole thing counts as karaoke.
     *
     * A file where two lines out of sixty carry inline stamps is a line-timed lyric with debris in it.
     * Rendering it as word-synced would flip the active line between two renderers as it scrolls, which
     * reads as flicker; treating the whole lyric as line-timed is both honest and stable.
     */
    private const val WORD_SYNCED_COVERAGE = 0.6

    /** Last resort when a line has no end and no successor: long enough to read, short enough to notice. */
    private const val TRAILING_LINE_MS = 4_000L

    fun normalize(lyrics: Lyrics): Lyrics {
        if (lyrics.lines.isEmpty()) return lyrics

        val repaired = lyrics.lines
            .map { it.repairWords() }
            .sortedBy(LyricLine::timeMs)
            .withoutLeadingCredits()
        val gated = if (repaired.isWordSyncedEnough()) repaired else repaired.map { it.copy(words = emptyList()) }
        val closed = gated.withLineEnds()

        return if (closed == lyrics.lines) lyrics else lyrics.copy(lines = closed)
    }

    /**
     * Drops the credits KuGou and NetEase files open with — "作词：Pdogg", "Lyrics by：David Stewart",
     * "Produced by：…" — each a *timed* line, so the karaoke view would open on a screen of them and
     * sweep across a producer's name. Only the run at the top goes; once a real line has been seen,
     * nothing after it is touched, so a lyric that happens to sing the words "written by" is safe.
     * Every line dropped is a credit by its shape (a role, then a colon), never by its content.
     */
    private fun List<LyricLine>.withoutLeadingCredits(): List<LyricLine> {
        val first = indexOfFirst { it.text.isNotBlank() && !CREDIT_LINE.containsMatchIn(it.text) }
        return if (first <= 0) this else drop(first)
    }

    /**
     * A credit line, by shape. Either a role word — in either script, optionally followed by its
     * translation ("作曲 Composer") or a second role ("Vocal Production/Engineering") — then ":" or
     * "："; or a bracketed list of names separated by slashes, which is how KuGou files open
     * ("(Harley Streten/Gregory Hein/JPEGMAFIA)"). The roles are the ones the two Chinese catalogues
     * actually write; the English forms are how KuGou renders them for K-pop.
     */
    private val CREDIT_LINE = Regex(
        """^\s*(?:(?:作词|作曲|编曲|作詞|編曲|词|曲|制作人|监制|混音|母带|录音|吉他|贝斯|鼓|键盘|和声|出品|发行|""" +
        """(?:OP|SP|Lyrics?|Composed?|Composer|Arranged?|Arrangement|Music|Words|Written|Produced?|Producer|""" +
        """Production|Mixed|Mixing|Mastered|Mastering|Recorded|Recording|Engineer(?:ed|ing)?|Vocals?|Chorus|""" +
        """Guitars?|Bass|Drums|Keyboards?|Synth|Programming|Additional|Publisher)\b)[^:：]{0,40}[:：]|\(.*/.*/.*)""",
        RegexOption.IGNORE_CASE,
    )

    /** Sorts a line's words, closes their ends, and drops the lot when they can't drive a sweep. */
    private fun LyricLine.repairWords(): LyricLine {
        if (words.isEmpty()) return this
        val fixed = words
            .map { it.copy(endMs = maxOf(it.endMs, it.startMs)) }
            .sortedBy(LyricWord::startMs)
        // A word with no duration has nothing to sweep across. If *every* word is like that the timings
        // are decorative — a line stamp repeated per word — and the line is better off line-timed.
        val usable = fixed.any { it.endMs > it.startMs } && fixed.any { it.text.isNotBlank() }
        return copy(words = if (usable) fixed else emptyList())
    }

    /** True when enough of the lyric survived [repairWords] to call the whole thing word-synced. */
    private fun List<LyricLine>.isWordSyncedEnough(): Boolean {
        // Blank lines are instrumental markers, not failures — they never carry words and must not count
        // against the coverage.
        val sung = count { it.text.isNotBlank() }
        if (sung == 0) return false
        val timed = count { it.text.isNotBlank() && it.words.isNotEmpty() }
        return timed.toDouble() / sung >= WORD_SYNCED_COVERAGE
    }

    /**
     * Gives every line an end. In order of trust: what the format said, then where the next line starts,
     * then where the line's own last word stopped, then a fixed tail for the final line of the song.
     */
    private fun List<LyricLine>.withLineEnds(): List<LyricLine> = mapIndexed { i, line ->
        val next = getOrNull(i + 1)?.timeMs
        val end = when {
            line.endMs > line.timeMs -> line.endMs
            next != null && next > line.timeMs -> next
            else -> line.words.lastOrNull()?.endMs?.takeIf { it > line.timeMs }
                ?: (line.timeMs + TRAILING_LINE_MS)
        }
        if (end == line.endMs) line else line.copy(endMs = end)
    }
}
