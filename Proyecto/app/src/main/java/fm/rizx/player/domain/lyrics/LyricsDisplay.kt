package fm.rizx.player.domain.lyrics

import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.Lyrics
import fm.rizx.player.domain.model.LyricsDisplayMode

/** The alternative reading of one line, or `null` when this line has none. */
private fun LyricLine.reading(mode: LyricsDisplayMode): String? = when (mode) {
    LyricsDisplayMode.ORIGINAL -> text
    LyricsDisplayMode.PRONUNCIATION -> romanized
    LyricsDisplayMode.TRANSLATION -> translated
}?.takeIf { it.isNotBlank() }

/**
 * The same lyric read a different way.
 *
 * The whole karaoke stack — the timeline, the sweep, the auto-scroll, the tap-to-seek — is written
 * against `List<LyricLine>` and knows nothing about scripts. So switching reading is not a rendering
 * feature: it is the same list with a different [LyricLine.text], and every screen below keeps working
 * untouched.
 *
 * Two rules make it safe:
 *
 *  - **Word timings are dropped.** A romanization is timed per *line* (NetEase's `romalrc` shares its
 *    timestamps with `lrc` and nothing finer), so keeping the original's per-word spans would sweep the
 *    wrong letters at the wrong moment. Without them `LyricsTimeline` falls back to the uniform sweep it
 *    already uses for line-timed lyrics.
 *  - **A line with no reading keeps its own text.** Sources skip lines rather than pad them — NetEase's
 *    `romalrc` omits the four credit lines of a 63-line file — and a blank where a lyric should be reads
 *    as a bug, while the original text reads as "this line needed no transcribing".
 */
fun Lyrics.inMode(mode: LyricsDisplayMode): Lyrics {
    if (mode == LyricsDisplayMode.ORIGINAL) return this
    if (lines.none { it.reading(mode) != null }) return this

    return copy(
        lines = lines.map { line ->
            line.copy(text = line.reading(mode) ?: line.text, words = emptyList())
        },
    )
}

/** The readings this lyric can actually offer, in the order the switch cycles through them. */
fun Lyrics.availableModes(): List<LyricsDisplayMode> = buildList {
    add(LyricsDisplayMode.ORIGINAL)
    if (hasRomanization) add(LyricsDisplayMode.PRONUNCIATION)
    if (hasTranslation) add(LyricsDisplayMode.TRANSLATION)
}
