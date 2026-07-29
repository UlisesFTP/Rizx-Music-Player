package fm.rizx.player.data.artwork

import fm.rizx.player.domain.match.RecordingIdentity
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.usecase.ArtistNameMatching

/**
 * Decides whether a search result may lend its cover to a track.
 *
 * The enricher used to take a catalogue's rank-1 hit unverified, which is how a remix's artwork ended
 * up on the original and how a stranger's "Intro" ended up on every song of that name. Borrowing a
 * cover is only safe when the candidate is the same *release*, so both halves must agree:
 *
 *  - **the title**, via [RecordingIdentity.sharesArtwork] — decoration ignored, remix/live/acoustic
 *    refused, remaster/radio-edit allowed (those ship under the original's art);
 *  - **the artist**, via [ArtistNameMatching.sameArtist] — which reads "DualipaVEVO" back as Dua Lipa,
 *    so a YouTube row is judged on who actually made the record.
 *
 * The artist half is what catches the case the title half can't: someone else's edit of a song is a
 * different record wearing a different sleeve, and its only tell is the name on it.
 *
 * A candidate with **no artist at all** is refused rather than accepted on the title alone. That is
 * the deliberate asymmetry: a missing cover is a placeholder, a wrong cover is a lie.
 */
object ArtworkMatching {

    /** True when [candidate] is the same release as [track] and may therefore lend its cover. */
    fun canLendArtwork(track: Track, candidate: Track): Boolean {
        if (!RecordingIdentity.sharesArtwork(track.title, candidate.title)) return false
        val mine = track.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() }
        val theirs = candidate.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() }
        // No artist on either side leaves only the title, and titles collide constantly.
        if (mine == null || theirs == null) return false
        return ArtistNameMatching.sameArtist(mine, theirs)
    }
}
