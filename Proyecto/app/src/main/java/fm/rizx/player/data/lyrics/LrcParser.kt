package fm.rizx.player.data.lyrics

import fm.rizx.player.domain.model.LyricLine

/**
 * Parses the LRC format — `[mm:ss.xx] text` — into timed [LyricLine]s.
 *
 * Pure Kotlin with no Android or network dependency, so the timing rules are unit-testable on the JVM.
 *
 * Handles what real files actually contain, which is more than LRCLIB happens to emit today:
 * - **Fractions of any width.** `[01:02.5]`, `[01:02.50]` and `[01:02.500]` are all 1 m 2.5 s. Treating
 *   the digits as a fixed hundredths field would put a 3-digit file 10× off.
 * - **Repeated lines share timestamps.** `[00:10.00][01:20.00] chorus` is the standard's way of writing a
 *   chorus once; each stamp becomes its own line.
 * - **Metadata tags are skipped** (`[ar:]`, `[ti:]`, `[al:]`, `[by:]`, `[re:]`, `[ve:]`, `[length:]`), but
 *   **`[offset:±ms]` is applied** — it exists precisely to correct a file that runs early or late.
 * - **Timestamped blank lines are kept** as instrumental gaps (see [LyricLine]).
 *
 * Anything unparseable is dropped rather than throwing: a malformed lyric must degrade to "fewer lines",
 * never to a crash on a screen the user just opened.
 */
object LrcParser {

    // Minutes can exceed 2 digits on long mixes; the fraction is optional and of any width.
    private val TIMESTAMP = Regex("""\[(\d{1,3}):([0-5]?\d)(?:[.:](\d{1,3}))?]""")
    private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val METADATA_TAG = Regex("""^\[[a-zA-Z#]+:[^]]*]$""")

    /**
     * Parses [raw] into lines sorted by time. Returns empty when [raw] carries no timestamps at all —
     * that is the caller's signal that the text is prose, not a synced lyric.
     */
    fun parse(raw: String?): List<LyricLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val offsetMs = OFFSET_TAG.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val lines = mutableListOf<LyricLine>()
        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || METADATA_TAG.matches(line)) continue

            val stamps = TIMESTAMP.findAll(line).toList()
            if (stamps.isEmpty()) continue

            // The text is whatever follows the last leading timestamp — so a bracketed aside inside the
            // words (e.g. "[laughs]") can't be mistaken for a stamp and truncate the line.
            val text = line.substring(stamps.last().range.last + 1).trim()
            for (stamp in stamps) {
                val at = timeOf(stamp) ?: continue
                // A negative offset can push the first lines before zero; clamp so seeking stays valid.
                lines += LyricLine(timeMs = (at + offsetMs).coerceAtLeast(0L), text = text)
            }
        }
        // Multi-timestamp choruses arrive out of order by construction.
        return lines.sortedBy(LyricLine::timeMs)
    }

    private fun timeOf(match: MatchResult): Long? {
        val (_, minutes, seconds, fraction) = match.groupValues
        val m = minutes.toLongOrNull() ?: return null
        val s = seconds.toLongOrNull() ?: return null
        // "5" -> 500 ms, "50" -> 500 ms, "500" -> 500 ms: scale by the digit count, don't assume hundredths.
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> (fraction.toLongOrNull() ?: 0L) * 100
            2 -> (fraction.toLongOrNull() ?: 0L) * 10
            else -> fraction.toLongOrNull() ?: 0L
        }
        return (m * 60 + s) * 1000 + millis
    }
}

/**
 * The index of the line being sung at [positionMs], or -1 before the first one.
 *
 * [offsetMs] shifts the *lyrics* relative to the audio: positive means "these words arrive later", which
 * is the correction needed when the played recording has a longer intro than the one the file was timed
 * against — routine here, since the audio usually comes from YouTube rather than the reference master.
 *
 * A plain scan: lyric files are ~60-120 lines and this runs only when the position actually crosses a
 * line boundary, so a binary search would buy nothing but a chance to get the edges wrong.
 */
fun List<LyricLine>.activeIndexAt(positionMs: Long, offsetMs: Long = 0L): Int {
    var index = -1
    for (i in indices) {
        if (this[i].timeMs + offsetMs <= positionMs) index = i else break
    }
    return index
}
