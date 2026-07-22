package fm.rizx.player.domain.provider

import fm.rizx.player.domain.model.Lyrics
import fm.rizx.player.domain.model.LyricsCandidate
import fm.rizx.player.domain.model.Track

/**
 * A lyrics provider (Phase 15): fetches lyrics for a [Track] — plain, timed, or both. A
 * [ProviderDescriptor] of kind [ProviderKind.LYRICS], so it lives in the registry and is single-active.
 * Returns `null` when no lyrics are found (a normal outcome, not an error); transport failures raise a
 * typed error.
 *
 * [searchLyrics] is **optional and capability-gated by its default**, the same shape
 * [MetadataProvider.albumDetail] uses: a provider with no search endpoint simply doesn't override it and
 * stays valid. It exists because an automatic match can pick the wrong recording, and the only reliable
 * fix is letting the user choose.
 */
interface LyricsProvider : ProviderDescriptor {
    suspend fun getLyrics(track: Track): Lyrics?

    /** Candidates for a free-text query, best match first. Empty when the provider can't search. */
    suspend fun searchLyrics(query: String): List<LyricsCandidate> = emptyList()
}
