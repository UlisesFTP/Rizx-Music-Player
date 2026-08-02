package fm.rizx.player.data.lossless

import fm.rizx.player.domain.lossless.LosslessCandidate
import fm.rizx.player.domain.lossless.LosslessIndexItem
import fm.rizx.player.domain.lossless.LosslessMatchEvidence
import fm.rizx.player.domain.lossless.LosslessMatcher
import fm.rizx.player.domain.match.RecordingIdentity
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.usecase.ArtistNameMatching
import kotlin.math.abs

/**
 * Decides whether an index row is really *this* recording.
 *
 * The reference implementation this was measured against matches on `contains`, which is how you end up
 * playing a different artist's song of the same name at higher fidelity. The guiding rule here is the
 * opposite: **a correct ordinary stream beats a wrong FLAC**, so every uncertainty resolves toward not
 * using the index.
 *
 * **Two stages, because the index is nearly empty.** The rows carry `song`, `artist` and `url` — no
 * album, no duration, no ISRC. Title and artist alone cannot separate an album cut from a single edit,
 * a radio version, or a re-recording, and the only remaining witness is the file itself. So
 * [candidates] narrows on metadata and [confirmWithDuration] decides, once 64 KiB of the actual file
 * has been read. Nothing is ever played on stage one alone.
 *
 * The album and ISRC weights below are implemented and tested but **never fire against that index**.
 * They are not speculative: an index someone hosts themselves can carry those fields, and the shape of
 * the scoring is what stops the minimal case from being mistaken for a corroborated one.
 *
 * Pure Kotlin, no Android, no network.
 */
class DefaultLosslessMatcher : LosslessMatcher {

    override fun candidates(track: Track, items: List<LosslessIndexItem>): List<LosslessCandidate> {
        // With nothing but a title to go on, "Intro" would match every album ever made. An unattributed
        // track therefore doesn't get to use a minimal index at all.
        val ourArtists = track.artists.map { it.name }.filter { it.isNotBlank() }
        if (ourArtists.isEmpty()) return emptyList()

        return items.mapNotNull { item -> score(track, ourArtists, item) }
            .sortedByDescending { it.matchScore }
    }

    override fun confirmWithDuration(
        track: Track,
        candidate: LosslessCandidate,
        flacDurationMs: Long,
    ): LosslessCandidate? {
        val ours = track.durationMs
        val reasons = candidate.evidence.reasons.toMutableList()

        // No duration on our side either. Both witnesses silent is not agreement — it is the case where
        // an album version and a single edit are indistinguishable, so it does not get played.
        if (ours == null || ours <= 0L) {
            reasons += "no track duration to compare"
            return finish(candidate, -NO_DURATION_PENALTY, durationMatched = null, reasons)
        }

        val drift = abs(flacDurationMs - ours)
        val tolerance = maxOf(TOLERANCE_FLOOR_MS, ours * TOLERANCE_PERCENT / 100)

        return when {
            drift <= tolerance -> {
                reasons += "duration within ${tolerance / 1000}s"
                finish(candidate, DURATION_CONFIRMED, durationMatched = true, reasons)
            }
            // Close but not close enough. Survivable only if something *other* than title and artist
            // corroborated — an album or an ISRC — which the minimal index never supplies. That is the
            // point: a 6-second difference with nothing else backing it is a different recording.
            drift <= DURATION_HARD_LIMIT_MS -> {
                reasons += "duration off by ${drift / 1000}s"
                finish(candidate, -DURATION_DOUBTFUL, durationMatched = false, reasons)
            }
            else -> null
        }
    }

    override fun tooCloseToCall(a: Int, b: Int): Boolean = abs(a - b) <= AMBIGUOUS_MARGIN

    private fun finish(
        candidate: LosslessCandidate,
        delta: Int,
        durationMatched: Boolean?,
        reasons: List<String>,
    ): LosslessCandidate? {
        val score = (candidate.matchScore + delta).coerceIn(0, MAX_SCORE)
        if (score < FINAL_THRESHOLD) return null
        return candidate.copy(
            matchScore = score,
            evidence = candidate.evidence.copy(durationMatched = durationMatched, reasons = reasons),
        )
    }

    /**
     * Stage one. Returns `null` for a hard incompatibility — a different version, a different artist, a
     * contradicting album/ISRC — rather than a low score, because those are statements that this is a
     * different recording and no amount of agreement elsewhere overrules them.
     */
    private fun score(track: Track, ourArtists: List<String>, item: LosslessIndexItem): LosslessCandidate? {
        // Shared with the lyrics matcher and the canvas: "(Remix)", "(Live)", "(Sped Up)", "(Karaoke)"
        // are the same recording only if both sides carry the same set.
        if (RecordingIdentity.versionTags(track.title) != RecordingIdentity.versionTags(item.song)) return null
        if (!RecordingIdentity.sameTitle(track.title, item.song)) return null

        // Split the billing before comparing. An index row credits a song the way a filename does —
        // "Yuvan Shankar Raja, Na. Muthukumar" for a track this app knows as "Yuvan Shankar Raja" — and
        // comparing against the joined string rejects every collaboration in the catalogue. Measured
        // against the reference index, that is a large share of it.
        val theirCredits = ArtistNameMatching.credits(item.artist)
        val artistMatched = ourArtists.any { ours ->
            theirCredits.any { theirs -> ArtistNameMatching.sameArtist(ours, theirs) }
        }
        if (!artistMatched) return null

        // Matching *somewhere* in the billing is enough to consider a row, but not enough to score like
        // the real thing: an artist who appears as a guest on somebody else's same-titled song has to
        // clear the threshold on other evidence, and on the minimal index there is none — so it stops here.
        val primaryAgrees = ourArtists.first().let { ours ->
            theirCredits.firstOrNull()?.let { theirs -> ArtistNameMatching.sameArtist(ours, theirs) } == true
        }
        val reasons = mutableListOf(if (primaryAgrees) "title and artist agree" else "title agrees, artist is a guest credit")
        var score = SAME_TITLE + if (primaryAgrees) PRIMARY_ARTIST else SECONDARY_CREDITS

        // A *second* credit lining up too is real corroboration: "Song / A, B" vs "Song / A" is weaker
        // than "Song / A, B" vs "Song / A, B".
        val secondaryAgrees = theirCredits.size > 1 &&
            ourArtists.size > 1 &&
            theirCredits.drop(1).any { theirs -> ourArtists.drop(1).any { ArtistNameMatching.sameArtist(it, theirs) } }
        if (secondaryAgrees) {
            score += SECONDARY_CREDITS
            reasons += "secondary credits agree"
        }

        val album = compareAlbum(track, item)
        if (album == Agreement.CONTRADICTS) return null
        if (album == Agreement.AGREES) {
            score += ALBUM_EXACT
            reasons += "album agrees"
        }

        val isrc = compareIsrc(track, item)
        if (isrc == Agreement.CONTRADICTS) return null
        if (isrc == Agreement.AGREES) {
            score += ISRC_EXACT
            reasons += "isrc agrees"
        }

        // An index that publishes durations lets a wrong edit be dropped before a byte is fetched.
        val indexDuration = compareIndexDuration(track, item)
        if (indexDuration == Agreement.CONTRADICTS) return null
        if (indexDuration == Agreement.AGREES) {
            score += INDEX_DURATION
            reasons += "index duration agrees"
        }

        // The identity gate closes *here*, before anything about the file's shape is counted. A URL
        // ending in `.flac` and a published checksum say something about the bytes and nothing at all
        // about which recording they are — letting five points of file extension be what admits a
        // borderline candidate is how a guest credit turns into a match.
        if (score < PRE_THRESHOLD) return null

        if (item.url.substringBefore('?').endsWith(".flac", ignoreCase = true)) score += FLAC_EXTENSION
        if (!item.sha256.isNullOrBlank()) score += CHECKSUM_OFFERED

        return LosslessCandidate(
            item = item,
            matchScore = score.coerceIn(0, MAX_SCORE),
            evidence = LosslessMatchEvidence(
                titleMatched = true,
                artistMatched = true,
                albumMatched = album.asEvidence(),
                isrcMatched = isrc.asEvidence(),
                reasons = reasons,
            ),
        )
    }

    /**
     * What one signal had to say. **Silence is not agreement** — the distinction the whole two-stage
     * design rests on, and the one a plain `Boolean` loses.
     */
    private enum class Agreement { AGREES, ABSENT, CONTRADICTS }

    /** `ABSENT` becomes `null` in the evidence: neither side offered this, so nobody disagreed either. */
    private fun Agreement.asEvidence(): Boolean? = if (this == Agreement.AGREES) true else null

    private fun compareAlbum(track: Track, item: LosslessIndexItem): Agreement {
        val ours = track.album?.title?.takeIf { it.isNotBlank() } ?: return Agreement.ABSENT
        val theirs = item.album?.takeIf { it.isNotBlank() } ?: return Agreement.ABSENT
        // Reusing the *title* normaliser on an album name is a small stretch — it also strips version
        // words — but "Deluxe Edition" vs "Deluxe" is exactly the noise it was written to absorb.
        return if (RecordingIdentity.sameTitle(ours, theirs)) Agreement.AGREES else Agreement.CONTRADICTS
    }

    private fun compareIsrc(track: Track, item: LosslessIndexItem): Agreement {
        val theirs = item.isrc?.takeIf { it.isNotBlank() }?.uppercase() ?: return Agreement.ABSENT
        // Rizx's Track has no ISRC field and none of its providers return one, so ours is always absent
        // today. Modelled rather than dropped because it is the one identifier that would make this
        // exact rather than probabilistic, and an index that carries it should not be ignored.
        val ours = track.tags.firstOrNull { it.startsWith(ISRC_TAG_PREFIX, ignoreCase = true) }
            ?.removePrefix(ISRC_TAG_PREFIX)?.removePrefix("=")?.trim()?.uppercase()
            ?.takeIf { it.isNotBlank() } ?: return Agreement.ABSENT
        return if (ours == theirs) Agreement.AGREES else Agreement.CONTRADICTS
    }

    private fun compareIndexDuration(track: Track, item: LosslessIndexItem): Agreement {
        val theirs = item.durationMs?.takeIf { it > 0L } ?: return Agreement.ABSENT
        val ours = track.durationMs?.takeIf { it > 0L } ?: return Agreement.ABSENT
        val drift = abs(theirs - ours)
        return when {
            drift <= TOLERANCE_FLOOR_MS -> Agreement.AGREES
            drift <= DURATION_HARD_LIMIT_MS -> Agreement.ABSENT // close enough to let the file decide
            else -> Agreement.CONTRADICTS
        }
    }

    private companion object {
        const val ISRC_TAG_PREFIX = "isrc"

        // Stage one. Title + artist alone reaches 85, which clears PRE_THRESHOLD and nothing more —
        // deliberately, so that the minimal index has to earn the rest from the file itself.
        const val SAME_TITLE = 50
        const val PRIMARY_ARTIST = 35
        const val SECONDARY_CREDITS = 15
        const val ALBUM_EXACT = 20
        const val ISRC_EXACT = 100
        const val INDEX_DURATION = 15
        const val FLAC_EXTENSION = 5
        const val CHECKSUM_OFFERED = 5

        // Stage two.
        const val DURATION_CONFIRMED = 10
        const val DURATION_DOUBTFUL = 10
        const val NO_DURATION_PENALTY = 15

        const val PRE_THRESHOLD = 70
        const val FINAL_THRESHOLD = 85
        const val MAX_SCORE = 200

        const val AMBIGUOUS_MARGIN = 5
        const val TOLERANCE_FLOOR_MS = 4_000L
        const val TOLERANCE_PERCENT = 3
        const val DURATION_HARD_LIMIT_MS = 10_000L
    }
}
