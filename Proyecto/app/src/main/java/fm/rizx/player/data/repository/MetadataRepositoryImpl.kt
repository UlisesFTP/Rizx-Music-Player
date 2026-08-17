package fm.rizx.player.data.repository

import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.DetailCapability
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.repository.MetadataRepository
import fm.rizx.player.domain.repository.NoMetadataProviderException
import kotlinx.coroutines.CancellationException

/**
 * Routes metadata operations to whichever [MetadataProvider] is currently active in the
 * [ProviderRegistry]. If none is active (or the active descriptor isn't a [MetadataProvider]),
 * throws [NoMetadataProviderException] so the caller can surface an error state. Detail lookups return
 * `null` when the active provider can't serve them (Phase 17).
 */
class MetadataRepositoryImpl(
    private val registry: ProviderRegistry,
    private val detailProviders: List<MetadataProvider> = emptyList(),
) : MetadataRepository {

    override suspend fun search(params: SearchParams): SearchResults = provider().search(params)

    override suspend fun albumDetail(source: ProviderRef): Album? =
        ownerFirst(source, DetailCapability.ALBUM_DETAIL) { it.albumDetail(source) }

    override suspend fun artistDetail(source: ProviderRef): Artist? =
        ownerFirst(source, DetailCapability.ARTIST_DETAIL) { it.artistDetail(source) }

    override suspend fun relatedArtists(source: ProviderRef): List<ArtistRef> =
        provider().relatedArtists(source)

    override suspend fun radioTracks(seed: Track): List<Track> = provider().radioTracks(seed)

    override suspend fun playlistTracks(source: ProviderRef): List<Track> = provider().playlistTracks(source)

    private fun provider(): MetadataProvider =
        registry.activeDescriptor(ProviderKind.METADATA) as? MetadataProvider
            ?: throw NoMetadataProviderException()

    private suspend fun <T> ownerFirst(
        source: ProviderRef,
        capability: DetailCapability,
        fetch: suspend (MetadataProvider) -> T?,
    ): T? {
        val active = provider()
        val candidates = (detailProviders + registry.list(ProviderKind.METADATA).filterIsInstance<MetadataProvider>())
            .distinctBy { it.id }
        val owners = candidates.filter {
            capability in it.detailCapabilities && source.provider in it.ownedNamespaces
        }
        for (candidate in owners) {
            safely { fetch(candidate) }?.let { return it }
        }
        if (active !in owners && capability in active.detailCapabilities) {
            return safely { fetch(active) }
        }
        return null
    }

    private suspend fun <T> safely(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
