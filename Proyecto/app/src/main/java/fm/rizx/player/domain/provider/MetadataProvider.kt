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

    /**
     * The `ProviderRef.provider` namespaces whose items belong to this provider.
     *
     * **This is not [id].** A provider's registry id names the *source you can select*
     * (`applemusic-metadata`), while the refs it mints name the *catalogue* (`applemusic`) — and one
     * catalogue can be served by more than one provider. Anything asking "who owns this item?" must
     * go through here; looking a provider up by `source.provider` silently finds nothing for every
     * provider whose two names differ, which is most of them.
     *
     * Defaults to [id] so a provider whose two names coincide needs no override.
     */
    val ownedNamespaces: Set<String> get() = setOf(id)

    /** True when [source] came from this provider's catalogue. */
    fun owns(source: ProviderRef): Boolean = source.provider in ownedNamespaces

    /**
     * The full [Track] behind one of **this provider's own** refs — an exact lookup by id, never a
     * search. This is what makes "go back to the owner" possible: the owner knows precisely which
     * recording a ref names, so its answer needs no matching and can never be the wrong song.
     *
     * Returns `null` when the provider can't resolve by id, doesn't own [source], or finds nothing.
     */
    suspend fun trackDetail(source: ProviderRef): Track? = null

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
