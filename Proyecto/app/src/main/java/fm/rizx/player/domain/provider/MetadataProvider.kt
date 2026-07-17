package fm.rizx.player.domain.provider

import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.DetailCapability
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track

/**
 * A metadata provider: searches a catalog and (optionally) fetches album/artist detail. Implementations
 * are also [ProviderDescriptor]s of kind [ProviderKind.METADATA], so they live in the registry.
 * The MVP uses a single [UNIFIED][SearchCapability.UNIFIED] [search].
 *
 * Detail lookups (Phase 17) are **optional/capability-gated**: a provider declares
 * [detailCapabilities] and overrides the matching method. Defaults keep existing providers valid
 * (they declare nothing and return `null`).
 */
interface MetadataProvider : ProviderDescriptor {
    val searchCapabilities: Set<SearchCapability>
    suspend fun search(params: SearchParams): SearchResults

    val detailCapabilities: Set<DetailCapability> get() = emptySet()

    /** Full album (incl. track list) for an album [source] ref, or `null` if unsupported/not found. */
    suspend fun albumDetail(source: ProviderRef): Album? = null

    /** Full artist (top tracks + albums) for an artist [source] ref, or `null` if unsupported/not found. */
    suspend fun artistDetail(source: ProviderRef): Artist? = null

    /**
     * ~25 similar/"radio" tracks seeded from a currently-playing [seed] track — powers the feed/search
     * radio so next/prev keep going. Default empty (providers without a radio endpoint opt out).
     */
    suspend fun radioTracks(seed: Track): List<Track> = emptyList()

    /** The playable tracks of a (remote/editorial) playlist [source] ref, or empty if unsupported. */
    suspend fun playlistTracks(source: ProviderRef): List<Track> = emptyList()
}
