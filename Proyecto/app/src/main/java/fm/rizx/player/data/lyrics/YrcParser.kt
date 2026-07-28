package fm.rizx.player.data.lyrics

import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.LyricWord

/**
 * Parses NetEase's **`yrc`** karaoke format into word-timed [LyricLine]s.
 *
 * A line looks like `[3930,990](3930,270,0)Call (4200,60,0)it` — a `[start,duration]` header in
 * milliseconds followed by one `(start,duration,0)` group per word, whose times are **absolute**, not
 * relative to the line.
 *
 * NetEase also prefixes the file with credit lines that are JSON rather than lyrics
 * (`{"t":0,"c":[{"tx":"作词: "},…]}`); those are skipped, otherwise the song would open on a screen of
 * raw JSON. Pure Kotlin, no Android — unit-testable on the JVM, and anything unparseable is dropped
 * rather than thrown.
 */
object YrcParser {

    private val LINE_HEADER = Regex("""^\[(\d+),(\d+)]""")
    private val WORD = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")

    fun parse(raw: String?): List<LyricLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val lines = mutableListOf<LyricLine>()
        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim()
            // The credit block is JSON objects, not timed lyrics.
            if (line.isEmpty() || line.startsWith("{")) continue
            val header = LINE_HEADER.find(line) ?: continue
            val lineStart = header.groupValues[1].toLongOrNull() ?: continue
            // The header's second field is the line's own duration. It used to be matched and thrown
            // away; it is what tells the sweep when a line is over rather than guessing from the next.
            val lineDuration = header.groupValues[2].toLongOrNull() ?: 0L

            val words = WORD.findAll(line).mapNotNull { it.toWord() }.toList()
            if (words.isEmpty()) continue
            lines += LyricLine(
                timeMs = lineStart,
                text = words.joinToString(separator = "") { it.text }.trim(),
                // A line of pure whitespace carries no words to light up individually.
                words = words.takeIf { w -> w.any { it.text.isNotBlank() } }.orEmpty(),
                endMs = if (lineDuration > 0L) lineStart + lineDuration else 0L,
            )
        }
        return lines.sortedBy(LyricLine::timeMs)
    }

    private fun MatchResult.toWord(): LyricWord? {
        val start = groupValues[1].toLongOrNull() ?: return null
        val duration = groupValues[2].toLongOrNull() ?: return null
        return LyricWord(startMs = start, endMs = start + duration, text = groupValues[3])
    }
}
