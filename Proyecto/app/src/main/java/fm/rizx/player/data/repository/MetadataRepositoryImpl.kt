package fm.rizx.player.data.repository

import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.repository.MetadataRepository
import fm.rizx.player.domain.repository.NoMetadataProviderException

/**
 * Routes metadata operations to whichever [MetadataProvider] is currently active in the
 * [ProviderRegistry]. If none is active (or the active descriptor isn't a [MetadataProvider]),
 * throws [NoMetadataProviderException] so the caller can surface an error state. Detail lookups return
 * `null` when the active provider can't serve them (Phase 17).
 */
class MetadataRepositoryImpl(
    private val registry: ProviderRegistry,
) : MetadataRepository {

    override suspend fun search(params: SearchParams): SearchResults = provider().search(params)

    override suspend fun albumDetail(source: ProviderRef): Album? = provider().albumDetail(source)

    override suspend fun artistDetail(source: ProviderRef): Artist? = provider().artistDetail(source)

    override suspend fun radioTracks(seed: Track): List<Track> = provider().radioTracks(seed)

    override suspend fun playlistTracks(source: ProviderRef): List<Track> = provider().playlistTracks(source)

    private fun provider(): MetadataProvider =
        registry.activeDescriptor(ProviderKind.METADATA) as? MetadataProvider
            ?: throw NoMetadataProviderException()
}
