package fm.rizx.player.data.provider

import fm.rizx.player.data.remote.spotify.SpotifyIds
import fm.rizx.player.domain.model.DetailCapability
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind

/** Detail-only adapter for Spotify chart albums; intentionally not registered as a search source. */
class SpotifyAlbumMetadataProvider(
    private val playlists: SpotifyPlaylistProvider,
) : MetadataProvider {
    override val id = "spotify-album-details"
    override val kind = ProviderKind.METADATA
    override val name = "Spotify album details"
    override val searchCapabilities: Set<SearchCapability> = emptySet()
    override val detailCapabilities = setOf(DetailCapability.ALBUM_DETAIL)
    override val ownedNamespaces = setOf(SpotifyIds.PROVIDER)
    override suspend fun search(params: SearchParams) = SearchResults()
    override suspend fun albumDetail(source: ProviderRef) = playlists.fetchAlbum(source)
}
