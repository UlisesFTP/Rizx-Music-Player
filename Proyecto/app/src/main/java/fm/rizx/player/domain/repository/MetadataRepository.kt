package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track

/**
 * Entry point for metadata operations. Routes to the **active** metadata provider chosen in the
 * registry; UI and use-cases depend on this, never on a provider implementation directly.
 * Throws [NoMetadataProviderException] when no metadata provider is active.
 */
interface MetadataRepository {
    suspend fun search(params: SearchParams): SearchResults

    /** Full album for [source] via the active provider; `null` if it can't serve it (Phase 17). */
    suspend fun albumDetail(source: ProviderRef): Album?

    /** Full artist for [source] via the active provider; `null` if it can't serve it (Phase 17). */
    suspend fun artistDetail(source: ProviderRef): Artist?

    /**
     * Artists similar to [source] via the active provider (empty when it doesn't publish any).
     *
     * Defaulted for the same reason as [fm.rizx.player.domain.playback.PlaybackController.playAutoRadio]:
     * it is an additive, optional capability, and every existing fake in the tests keeps compiling.
     */
    suspend fun relatedArtists(source: ProviderRef): List<ArtistRef> = emptyList()

    /** ~25 similar/radio tracks seeded from [seed] via the active provider (empty if unsupported). */
    suspend fun radioTracks(seed: Track): List<Track>

    /** Tracks of a remote/editorial playlist [source] via the active provider (empty if unsupported). */
    suspend fun playlistTracks(source: ProviderRef): List<Track>
}

class NoMetadataProviderException : Exception("No metadata provider is active")
