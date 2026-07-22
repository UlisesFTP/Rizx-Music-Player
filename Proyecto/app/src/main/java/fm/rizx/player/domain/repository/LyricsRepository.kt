package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.LyricsCandidate
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.TrackLyrics

/**
 * Resolves lyrics for a track and remembers what the user decided about them.
 *
 * Returns `null` when nothing is found (a normal outcome, not an error); transport failures propagate so
 * the UI can tell "nobody transcribed this" apart from "you're offline".
 */
interface LyricsRepository {

    /** The lyrics to show for [track] — the user's pick if there is one, else cache, else the providers. */
    suspend fun lyricsFor(track: Track): TrackLyrics?

    /** Free-text candidates for the manual picker. Empty when no provider can search. */
    suspend fun search(query: String): List<LyricsCandidate>

    /** Pins [candidate] as *the* lyrics for [track], overriding automatic matching from now on. */
    suspend fun pin(track: Track, candidate: LyricsCandidate)

    /** Shifts the words [offsetMs] against the audio (positive = they arrive later) and remembers it. */
    suspend fun setOffset(track: Track, offsetMs: Long)

    /** Forgets the pick, the offset and the cached copy, so the next lookup resolves from scratch. */
    suspend fun clearOverride(track: Track)
}
