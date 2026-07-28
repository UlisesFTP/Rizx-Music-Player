package fm.rizx.player.domain.lyrics

import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.LyricWord

/**
 * The primitives the karaoke view runs on: which line, which word, how far into it.
 *
 * These live in `domain` rather than next to the parsers because they are not parsing — every format
 * ends up here, and the answers must be identical whichever one the words came from.
 *
 * **Times are raw.** Nothing in this file applies the user's lyrics offset; [LyricsTimeline] subtracts it
 * once at the top of the pipeline and passes a corrected instant down. Applying it again here is how a
 * feature like this ends up double-shifted.
 */

/**
 * The index of the line being sung at [positionMs], or `-1` before the first one.
 *
 * [offsetMs] shifts the *lyrics* relative to the audio: positive means "these words arrive later", which
 * is the correction needed when the played recording has a longer intro than the one the file was timed
 * against — routine here, since the audio usually comes from YouTube rather than the reference master.
 *
 * Binary search rather than a scan: at 4 Hz the difference was academic, but the sweep asks this question
 * once per frame. Lines are sorted by construction (every parser sorts, and `LyricsNormalizer` sorts
 * again), and when several share a timestamp the **last** of them wins — a repeated chorus line is
 * emitted once per stamp, and the one that starts latest is the one now being sung.
 */
fun List<LyricLine>.activeIndexAt(positionMs: Long, offsetMs: Long = 0L): Int {
    val at = positionMs - offsetMs
    var lo = 0
    var hi = size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].timeMs <= at) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

/**
 * How many of this line's words have been sung at [positionMs] — i.e. the length of the highlighted
 * prefix, `0` before the first word and `words.size` once the line is done.
 *
 * A count rather than an index because that is what the caller actually needs: the split point between
 * the sung part of the line and the rest. Lines without word timings always return 0, so the caller falls
 * back to lighting the whole line. Still used by the battery-saver renderer, which steps word by word
 * instead of sweeping.
 */
fun LyricLine.sungWordCountAt(positionMs: Long, offsetMs: Long = 0L): Int {
    if (words.isEmpty()) return 0
    val at = positionMs - offsetMs
    var count = 0
    for (word in words) {
        if (word.startMs <= at) count++ else break
    }
    return count
}

/** The index of the word being sung at [positionMs], `-1` before the first one starts. */
fun LyricLine.wordIndexAt(positionMs: Long): Int = sungWordCountAt(positionMs) - 1

/**
 * How far through this word [positionMs] is: `0` before it starts, `1` once it's done.
 *
 * A word with no duration counts as finished the instant it starts. `LyricsNormalizer` drops those when
 * a lyric is made *entirely* of them, but a single zero-length word among real ones is common enough —
 * and stalling the sweep on it would look like the song stopped.
 */
fun LyricWord.progressAt(positionMs: Long): Float {
    if (endMs <= startMs) return if (positionMs >= startMs) 1f else 0f
    if (positionMs <= startMs) return 0f
    if (positionMs >= endMs) return 1f
    return ((positionMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
}

/**
 * How far through the *line* [positionMs] is, spread evenly from its start to its end.
 *
 * This is the line-synced fallback: the source gave one timestamp for the whole phrase, so the sweep is
 * an honest interpolation rather than a measurement. It is deliberately never promoted to
 * `WORD_SYNCED` — a phrase with a long pause in it will run ahead of the singer, and the screen keeps
 * saying "Line" so that reads as an estimate instead of a bug.
 */
fun LyricLine.uniformProgressAt(positionMs: Long): Float {
    val span = endMs - timeMs
    if (span <= 0L) return if (positionMs >= timeMs) 1f else 0f
    return ((positionMs - timeMs).toFloat() / span).coerceIn(0f, 1f)
}
