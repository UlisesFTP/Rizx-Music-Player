package fm.rizx.player.domain.model

import kotlinx.serialization.Serializable

/**
 * One word (or syllable) of a karaoke-timed line, with times **absolute** to the track.
 *
 * [text] keeps its own trailing space, so concatenating a line's words reproduces [LyricLine.text]
 * exactly — the UI splits the line at a word boundary rather than re-joining it.
 */
@Serializable
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

/**
 * One timed line of a synced lyric.
 *
 * An **empty [text] is meaningful, not junk**: LRC files mark instrumental gaps with a timestamp and no
 * words (8 of the 65 lines in a typical file), which is what lets the UI stop highlighting a stale line
 * during a solo instead of leaving the last sung line lit for a minute.
 *
 * [words] is the karaoke layer: sources that time each word (NetEase `yrc`, KuGou `krc`, Musixmatch
 * richsync, enhanced LRC) fill it so the active line can light up progressively. It defaults to empty,
 * which keeps line-only providers valid **and** lets lyrics cached before this existed still decode.
 */
@Serializable
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
)

/**
 * Lyrics for a track: plain text, timed lines, or both.
 *
 * Providers differ in what they can offer — lyrics.ovh has only prose, LRCLIB usually has both — so
 * [lines] being empty is the normal "unsynced" case rather than an error, and [isSynced] is the single
 * question the UI asks.
 */
@Serializable
data class Lyrics(
    val plain: String? = null,
    val lines: List<LyricLine> = emptyList(),
    /** Human-readable origin ("LRCLIB", "lyrics.ovh") — shown in the UI as attribution. */
    val sourceName: String = "",
    /** The provider positively declared the track has no words, as opposed to simply not knowing. */
    val instrumental: Boolean = false,
) {
    val isSynced: Boolean get() = lines.isNotEmpty()

    /** True when at least one line carries word timings, i.e. the karaoke view is possible. */
    val isWordSynced: Boolean get() = lines.any { it.words.isNotEmpty() }

    /** True when there is nothing at all to show (and the track isn't a declared instrumental). */
    val isEmpty: Boolean get() = lines.isEmpty() && plain.isNullOrBlank() && !instrumental
}

/**
 * [Lyrics] as they apply to one track: the words plus the two things the user gets to decide — how far
 * they are shifted against this particular recording, and whether they chose this version by hand.
 *
 * [offsetMs] lives here rather than in [Lyrics] because it describes the *pairing* of words and audio,
 * not the transcription: the same lyric file needs a different correction against a YouTube upload than
 * against a downloaded master.
 */
data class TrackLyrics(
    val lyrics: Lyrics,
    val offsetMs: Long = 0L,
    val pinned: Boolean = false,
)

/**
 * A candidate the user can pick from when the automatic match is wrong or out of sync.
 *
 * Carries its [lyrics] inline on purpose: LRCLIB's search response already embeds the full text of every
 * result, so applying a pick costs no second request and works with the network already gone.
 */
data class LyricsCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val lyrics: Lyrics,
)
