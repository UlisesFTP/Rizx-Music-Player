package fm.rizx.player.domain.canvas

import fm.rizx.player.domain.match.RecordingIdentity
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.usecase.ArtistNameMatching
import kotlin.math.abs

/** A candidate video reduced to what can actually be compared. Every provider maps its own rows to this. */
data class CanvasMatchTarget(
    val title: String,
    val artist: String? = null,
    val durationMs: Long? = null,
)

/**
 * Decides whether a video is really *this* song.
 *
 * The old canvas took the first search result that was a playable video and put it behind the artwork.
 * That works until it doesn't: search for "GIRLS" and YouTube will happily lead with a different song, a
 * reaction video or a slowed edit, and a wrong video is far more obviously wrong than a wrong lyric —
 * it's the whole screen.
 *
 * So this scores 0-100 and refuses below a threshold, and the refusal ends in the static cover. **A
 * missing canvas is a much better outcome than the wrong one**, which is the trade this whole class
 * exists to make.
 *
 * Version tags are a hard reject, not a deduction: if we're playing the album version and the candidate
 * is a remix, no amount of title agreement makes it the same recording. That gate is shared with the
 * lyrics matcher ([RecordingIdentity]) so both features draw the line in the same place.
 *
 * Pure Kotlin, no Android.
 */
object CanvasTrackMatcher {

    /** Score at or above which a candidate is simply accepted. */
    const val ACCEPT = 90

    /** Below this, rejected outright. Between the two, only a candidate whose title *and* artist lined up. */
    const val CORROBORATE = 80

    /** Two otherwise-equivalent candidates within this many points of each other are a coin flip. */
    const val AMBIGUOUS_MARGIN = 5

    private const val WRONG_TITLE = 45
    private const val WRONG_ARTIST = 25
    private const val UNKNOWN_DURATION = 8
    private const val DURATION_DRIFT_FAR = 15
    private const val DURATION_DRIFT_NEAR = 5

    /**
     * A music video is not the same length as the track.
     *
     * It opens on a scene and ends on credits, so 20-40 s longer than the audio is the *normal* case, not
     * a warning sign. Penalising that the way the lyrics matcher penalises drift would throw away
     * precisely the official videos this feature is for.
     */
    private const val DRIFT_FORGIVEN_MS = 20_000L
    private const val DRIFT_TOLERATED_MS = 60_000L

    /** How well [target] fits [track], 0-100 — or `null` when it is a different recording entirely. */
    fun score(track: Track, target: CanvasMatchTarget): Int? {
        if (RecordingIdentity.versionTags(track.title) != RecordingIdentity.versionTags(target.title)) {
            return null
        }
        var score = 100
        if (!titleAgrees(track, target)) score -= WRONG_TITLE
        if (!artistAgrees(track, target)) score -= WRONG_ARTIST
        score -= durationPenalty(track.durationMs, target.durationMs)
        return score.coerceIn(0, 100)
    }

    /**
     * Whether [score] is good enough to show.
     *
     * The middle band exists because duration is the one signal that drifts for honest reasons. A
     * candidate that lost points *only* on length still gets through; one that also disagreed on the
     * title or the artist does not.
     */
    fun accepts(score: Int, corroborated: Boolean): Boolean = when {
        score >= ACCEPT -> true
        score >= CORROBORATE -> corroborated
        else -> false
    }

    /**
     * The best of [targets] for [track], or `null` when none is acceptable.
     *
     * Ties break toward the earlier candidate, which for a search provider means the more relevant one.
     */
    fun <T> bestOf(track: Track, targets: List<T>, asTarget: (T) -> CanvasMatchTarget): Scored<T>? =
        rankAll(track, targets, asTarget).firstOrNull()

    /**
     * Every acceptable candidate, best first — the input to a caller that wants to rank by something
     * else as well (the YouTube provider sorts by [CanvasStaticFilter] tier first) or that keeps a
     * runner-up to fall back on.
     *
     * Stable: equal scores keep search order, which for a search provider means the more relevant one
     * stays ahead.
     */
    fun <T> rankAll(
        track: Track,
        targets: List<T>,
        asTarget: (T) -> CanvasMatchTarget,
    ): List<Scored<T>> = targets.mapNotNull { candidate ->
        val target = asTarget(candidate)
        val score = score(track, target) ?: return@mapNotNull null
        val corroborated = titleAgrees(track, target) && artistAgrees(track, target)
        if (!accepts(score, corroborated)) null else Scored(candidate, score)
    }.sortedByDescending { it.score }

    /**
     * Whether two candidates are too close to choose between.
     *
     * A search that answers with two *different* videos of near-equal merit has not identified the
     * song's video — it has offered a coin flip, and the caller's job is to refuse it rather than take
     * whichever came back first. This is the rule that stops "GIRLS" from picking somebody else's
     * "Girls" simply because YouTube ranked it top.
     *
     * Callers apply it only among candidates that are otherwise equivalent: a video on the artist's own
     * channel outranking a stranger's is not a tie, however close the scores.
     */
    fun tooCloseToCall(a: Int, b: Int): Boolean = abs(a - b) <= AMBIGUOUS_MARGIN

    /** A candidate that passed, carrying the score so the diagnostics can show it. */
    data class Scored<T>(val value: T, val score: Int)

    /**
     * Whether the title *and* the artist both lined up — the "corroborated" input to [accepts].
     *
     * Exposed because a caller that ranks candidates itself (rather than taking [bestOf]'s pick) still
     * has to apply the same middle-band rule, and re-deriving it would let the two drift.
     */
    fun sameRecording(track: Track, title: String, artist: String?): Boolean {
        val target = CanvasMatchTarget(title, artist)
        return titleAgrees(track, target) && artistAgrees(track, target)
    }

    private fun titleAgrees(track: Track, target: CanvasMatchTarget): Boolean =
        target.title.isBlank() || RecordingIdentity.sameTitle(track.title, target.title)

    /**
     * Unknown on either side counts as agreement rather than a penalty — the artist is the signal we are
     * *least* likely to have, and punishing its absence would reject everything a bare-titled upload
     * offers. [ArtistNameMatching] is what lets "DualipaVEVO" match "Dua Lipa".
     */
    private fun artistAgrees(track: Track, target: CanvasMatchTarget): Boolean {
        val theirs = target.artist?.takeIf { it.isNotBlank() } ?: return true
        val ours = track.artists.map { it.name }.filter { it.isNotBlank() }
        if (ours.isEmpty()) return true
        return ours.any { ArtistNameMatching.sameArtist(it, theirs) }
    }

    private fun durationPenalty(ours: Long?, theirs: Long?): Int = when {
        ours == null || theirs == null -> UNKNOWN_DURATION
        abs(theirs - ours) <= DRIFT_FORGIVEN_MS -> 0
        abs(theirs - ours) <= DRIFT_TOLERATED_MS -> DURATION_DRIFT_NEAR
        else -> DURATION_DRIFT_FAR
    }
}
