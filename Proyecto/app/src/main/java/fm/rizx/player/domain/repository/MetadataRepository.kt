package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.Artist
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

    /** ~25 similar/radio tracks seeded from [seed] via the active provider (empty if unsupported). */
    suspend fun radioTracks(seed: Track): List<Track>

    /** Tracks of a remote/editorial playlist [source] via the active provider (empty if unsupported). */
    suspend fun playlistTracks(source: ProviderRef): List<Track>
}

class NoMetadataProviderException : Exception("No metadata provider is active")
