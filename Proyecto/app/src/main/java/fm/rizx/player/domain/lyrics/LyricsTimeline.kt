package fm.rizx.player.domain.lyrics

import fm.rizx.player.domain.model.LyricLine
import java.text.BreakIterator
import kotlin.math.floor

/**
 * Where the sweep has got to: which line, and the grapheme it is currently crossing.
 *
 * [fromChar] and [toChar] bracket that grapheme in the line's text and [fraction] says how far across it
 * the edge is, so the renderer can turn three numbers into a pixel with `getHorizontalPosition` and never
 * has to know anything about words, timings or Unicode.
 */
data class LyricsSweep(
    val lineIndex: Int,
    val fromChar: Int,
    val toChar: Int,
    val fraction: Float,
) {
    companion object {
        /** Before the first line — nothing is being sung. */
        val None = LyricsSweep(lineIndex = -1, fromChar = 0, toChar = 0, fraction = 0f)
    }
}

/**
 * A lyric pre-chewed for playback: line starts indexed for binary search, grapheme boundaries measured,
 * and every word mapped onto the characters it actually covers.
 *
 * All of that is computed **once per lyric**, because the alternative is doing it sixty times a second.
 * What is left at frame time is one binary search and two multiplications.
 *
 * This is also the **single place the user's lyrics offset is applied**. [stateAt] subtracts it once and
 * everything downstream works in raw track time; the screen's tap-to-seek does the inverse conversion.
 * The old code applied it in three separate places, which is exactly how a sweep and a line highlight end
 * up disagreeing with each other.
 */
class LyricsTimeline private constructor(
    val lines: List<LyricLine>,
    /** Per line: char offsets of every grapheme boundary, `0 … text.length`. */
    private val graphemes: Array<IntArray>,
    /**
     * Per line: two grapheme indices per word (start, end). `null` when the words could not be located
     * in the line's own text, in which case the line falls back to the uniform sweep.
     */
    private val wordSpans: Array<IntArray?>,
) {

    /**
     * The sweep position at [positionMs] of audio, with the user's [offsetMs] correction applied.
     *
     * Positive [offsetMs] means "these words arrive later than the audio", matching the sign the offset
     * strip shows.
     */
    fun stateAt(positionMs: Long, offsetMs: Long): LyricsSweep {
        // The one and only application of the offset.
        val at = positionMs - offsetMs
        val index = lines.activeIndexAt(at)
        if (index < 0) return LyricsSweep.None

        val bounds = graphemes[index]
        val total = Graphemes.count(bounds)
        // A blank line is an instrumental marker: there is nothing to sweep, only a line to make active.
        if (total == 0) return LyricsSweep(index, 0, 0, 0f)

        val position = graphemePositionAt(index, bounds, total, at)
        val k = floor(position).toInt().coerceIn(0, total)
        val fraction = if (k >= total) 0f else (position - k).coerceIn(0f, 1f)
        return LyricsSweep(
            lineIndex = index,
            fromChar = bounds[k],
            toChar = bounds[minOf(k + 1, total)],
            fraction = fraction,
        )
    }

    /** How many graphemes of line [index] have been sung, as a fractional position in `0..total`. */
    private fun graphemePositionAt(index: Int, bounds: IntArray, total: Int, at: Long): Float {
        val line = lines[index]
        val spans = wordSpans[index]
        // Line-timed, or word-timed but unmappable: spread the line's own duration across its letters.
        if (spans == null) return line.uniformProgressAt(at) * total

        val word = line.wordIndexAt(at)
        if (word < 0) return 0f
        val start = spans[2 * word]
        val end = spans[2 * word + 1]
        if (end <= start) return end.toFloat()
        return start + line.words[word].progressAt(at) * (end - start)
    }

    companion object {
        val Empty = LyricsTimeline(emptyList(), emptyArray(), arrayOf())

        fun of(lines: List<LyricLine>): LyricsTimeline {
            if (lines.isEmpty()) return Empty
            // One BreakIterator for the whole lyric: constructing one per line is the expensive part.
            val iterator = BreakIterator.getCharacterInstance()
            val graphemes = Array(lines.size) { Graphemes.boundaries(lines[it].text, iterator) }
            val spans = Array<IntArray?>(lines.size) { spansFor(lines[it], graphemes[it]) }
            return LyricsTimeline(lines, graphemes, spans)
        }

        /**
         * Maps each word onto a grapheme range of the line's text.
         *
         * Located by searching forward rather than by summing lengths, because the four formats disagree
         * about what the line's text *is*: yrc and krc build it from the words, richsync ships a separate
         * `x` field that occasionally differs, and enhanced LRC drops any text sitting before the first
         * inline stamp. Searching survives all three; when it can't, the line says so by returning `null`
         * and gets the uniform sweep instead of a wrong one.
         */
        private fun spansFor(line: LyricLine, bounds: IntArray): IntArray? {
            val words = line.words
            if (words.isEmpty()) return null
            val text = line.text
            val out = IntArray(words.size * 2)
            var cursor = 0
            for ((i, word) in words.withIndex()) {
                val piece = word.text.trim()
                val start: Int
                val end: Int
                if (piece.isEmpty()) {
                    // A timed run of whitespace: real in richsync, and worth sweeping across so the edge
                    // keeps moving between two words instead of stalling.
                    start = cursor
                    end = (cursor + word.text.length).coerceAtMost(text.length)
                } else {
                    val found = text.indexOf(piece, cursor)
                    if (found < 0) return null
                    start = found
                    end = found + piece.length
                }
                // Snapped to cluster edges, so a word that begins mid-cluster can't split one.
                out[2 * i] = Graphemes.indexAt(bounds, start)
                out[2 * i + 1] = Graphemes.indexAt(bounds, end)
                cursor = end
            }
            return out
        }
    }
}
