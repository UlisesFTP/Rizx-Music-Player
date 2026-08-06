package fm.rizx.player.data.recognition

import fm.rizx.player.domain.match.RecordingIdentity
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.recognition.RecognitionMatch
import fm.rizx.player.domain.usecase.ArtistNameMatching

/**
 * Decides whether a catalogue row is the recording that was just identified.
 *
 * The service has already answered the hard question — *what* was playing — so this is only the
 * lookup: find that recording in a catalogue that also holds live takes, karaoke versions, covers,
 * sped-up edits and other artists' same-titled songs. Getting it wrong is worse than getting nothing,
 * because "no match, search for it?" is honest while playing a karaoke backing track is not. So every
 * uncertainty resolves towards `null`.
 *
 * Shaped after `DefaultLosslessMatcher`, and for the same reason: hard rejects for statements of
 * difference, points for corroboration, and a threshold that title-alone cannot reach.
 *
 * **Duration is deliberately absent.** Catalogue rows carry one, but the recognition service does not,
 * and a signal only one side supplies is not a signal.
 *
 * Pure Kotlin, no Android, no network.
 */
internal class RecognitionMatcher {

    /** The best candidate that clears the bar, or `null`. Ties keep the catalogue's own ranking. */
    fun best(match: RecognitionMatch, candidates: List<Track>): Track? =
        candidates.mapIndexedNotNull { rank, candidate -> score(match, candidate)?.let { Triple(it, rank, candidate) } }
            .filter { it.first >= THRESHOLD }
            .sortedWith(compareByDescending<Triple<Int, Int, Track>> { it.first }.thenBy { it.second })
            .firstOrNull()
            ?.third

    /**
     * Whether one specific candidate is that recording — used to sanity-check a row reached by an exact
     * identifier, where the only real risk is having been handed a different edition entirely.
     */
    fun accepts(match: RecognitionMatch, candidate: Track): Boolean =
        (score(match, candidate) ?: 0) >= THRESHOLD

    /**
     * `null` for a hard incompatibility rather than a low score: a different version, a different
     * artist or a contradicting album are *statements* that this is another recording, and no amount
     * of agreement elsewhere overrules them.
     */
    private fun score(match: RecognitionMatch, candidate: Track): Int? {
        // "(Live)", "(Remix)", "(Sped Up)", "(Karaoke)" — the same recording only if both sides carry
        // the same set. This is what stops a karaoke backing track from answering a recognition of the
        // real song, without needing a blocklist of suspicious words.
        if (RecordingIdentity.versionTags(match.title) != RecordingIdentity.versionTags(candidate.title)) return null
        if (!RecordingIdentity.sameTitle(match.title, candidate.title)) return null

        // The service bills a track as one line — "Daft Punk, Pharrell Williams & Nile Rodgers" —
        // while the catalogue splits it. Comparing the joined string against a single credit rejects
        // every collaboration there is.
        val theirs = ArtistNameMatching.credits(match.artist)
        val ours = candidate.artists.map { it.name }.filter { it.isNotBlank() }
        if (ours.isEmpty() || theirs.isEmpty()) return null
        if (ours.none { name -> theirs.any { ArtistNameMatching.sameArtist(name, it) } }) return null

        // Appearing anywhere in the billing is enough to consider a row; being the act it is credited
        // to is what makes it convincing. A guest credit has to earn the rest from the album.
        val primaryAgrees = ArtistNameMatching.sameArtist(ours.first(), theirs.first())
        var score = SAME_TITLE + if (primaryAgrees) PRIMARY_ARTIST else SECONDARY_CREDITS

        if (theirs.size > 1 && ours.size > 1 &&
            theirs.drop(1).any { t -> ours.drop(1).any { ArtistNameMatching.sameArtist(it, t) } }
        ) {
            score += SECONDARY_CREDITS
        }

        // Only ever a bonus. A differing album name is the normal state of affairs — the same recording
        // sits on the original release, a compilation, a deluxe reissue and a regional edition — so it
        // separates good candidates from better ones without being allowed to veto any of them. A
        // penalty here would sink an otherwise perfect title-and-artist agreement below the threshold
        // for the crime of having been found on a greatest-hits album.
        if (agreementOn(match.album, candidate.album?.title) == Agreement.AGREES) score += ALBUM_EXACT

        when (isrcAgreement(match, candidate)) {
            Agreement.AGREES -> score += ISRC_EXACT
            Agreement.CONTRADICTS -> return null // the one identifier that cannot be a near miss
            Agreement.ABSENT -> Unit
        }

        return score
    }

    /** Silence is not agreement — the distinction a plain `Boolean` loses. */
    private enum class Agreement { AGREES, ABSENT, CONTRADICTS }

    private fun agreementOn(theirs: String?, ours: String?): Agreement {
        val a = theirs?.takeIf { it.isNotBlank() } ?: return Agreement.ABSENT
        val b = ours?.takeIf { it.isNotBlank() } ?: return Agreement.ABSENT
        return if (RecordingIdentity.sameTitle(a, b)) Agreement.AGREES else Agreement.CONTRADICTS
    }

    private fun isrcAgreement(match: RecognitionMatch, candidate: Track): Agreement {
        val theirs = match.isrc?.takeIf { it.isNotBlank() }?.uppercase() ?: return Agreement.ABSENT
        val ours = candidate.tags.firstOrNull { it.startsWith(ISRC_TAG, ignoreCase = true) }
            ?.removePrefix(ISRC_TAG)?.removePrefix("=")?.trim()?.uppercase()
            ?.takeIf { it.isNotBlank() } ?: return Agreement.ABSENT
        return if (ours == theirs) Agreement.AGREES else Agreement.CONTRADICTS
    }

    private companion object {
        const val ISRC_TAG = "isrc"

        // Title and the credited artist reach exactly the threshold: that is the ordinary correct case.
        // Title and a guest credit reach 65, and need the album to agree before anything is played.
        const val SAME_TITLE = 50
        const val PRIMARY_ARTIST = 35
        const val SECONDARY_CREDITS = 15
        const val ALBUM_EXACT = 20
        const val ISRC_EXACT = 100

        const val THRESHOLD = 85
    }
}
