package fm.rizx.player.data.lyrics

import fm.rizx.player.domain.model.LyricLine

/**
 * Attaches a pronunciation and/or a translation to lines that already have their timings.
 *
 * Sources ship the alternative readings as **separate LRC documents**, not as extra columns, so the two
 * have to be stitched back together. They are joined **by timestamp, never by index**: NetEase's
 * `romalrc` omits the credit lines its `lrc` carries (59 stamps against 63 for a typical file), so
 * walking both lists in step drifts by four lines from the first chorus onwards.
 *
 * A stamp with no counterpart simply gets no reading, and the UI falls back to the original text for
 * that line.
 */
fun List<LyricLine>.withReadings(
    romanized: List<LyricLine> = emptyList(),
    translated: List<LyricLine> = emptyList(),
): List<LyricLine> {
    if (romanized.isEmpty() && translated.isEmpty()) return this

    val roman = romanized.byTime()
    val trans = translated.byTime()
    return map { line ->
        line.copy(
            romanized = roman[line.timeMs] ?: line.romanized,
            translated = trans[line.timeMs] ?: line.translated,
        )
    }
}

/**
 * The same, for a source that has no timings at all and can only be joined on the words themselves.
 *
 * Musixmatch's crowd translations are keyed by `matched_line`, a verbatim copy of the original line —
 * and the line on screen may well have come from a *different* provider, so the two spellings have to be
 * reconciled. Both sides are reduced to their letters and digits before comparing, which absorbs the
 * punctuation and spacing drift between two transcriptions of the same song without ever matching two
 * genuinely different lines.
 */
fun List<LyricLine>.withReadingsByText(
    romanized: Map<String, String> = emptyMap(),
    translated: Map<String, String> = emptyMap(),
): List<LyricLine> {
    if (romanized.isEmpty() && translated.isEmpty()) return this

    val roman = romanized.byWords()
    val trans = translated.byWords()
    return map { line ->
        val key = line.text.wordKey()
        if (key.isEmpty()) line else line.copy(
            romanized = roman[key] ?: line.romanized,
            translated = trans[key] ?: line.translated,
        )
    }
}

/** Letters and digits only, lowercased — everything two transcriptions can legitimately disagree on. */
private fun String.wordKey(): String = buildString(length) {
    for (ch in this@wordKey) if (ch.isLetterOrDigit()) append(ch.lowercaseChar())
}

private fun Map<String, String>.byWords(): Map<String, String> {
    if (isEmpty()) return emptyMap()
    val out = HashMap<String, String>(size)
    for ((from, to) in this) {
        val key = from.wordKey()
        if (key.isEmpty() || to.isBlank()) continue
        out.putIfAbsent(key, to)
    }
    return out
}

/**
 * Timestamp to text. First one wins: a repeated chorus written as `[t1][t2]same words` becomes two
 * lines with the same text but different stamps, so collisions are ties rather than conflicts.
 */
private fun List<LyricLine>.byTime(): Map<Long, String> {
    if (isEmpty()) return emptyMap()
    val out = HashMap<Long, String>(size)
    for (line in this) {
        val text = line.text.takeIf { it.isNotBlank() } ?: continue
        out.putIfAbsent(line.timeMs, text)
    }
    return out
}
