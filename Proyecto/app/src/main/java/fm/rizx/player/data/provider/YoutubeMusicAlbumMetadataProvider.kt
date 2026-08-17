package fm.rizx.player.data.provider

import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.YoutubeIds
import fm.rizx.player.data.remote.youtube.toArtworkSet
import fm.rizx.player.data.remote.youtube.toTrackOrNull
import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.DetailCapability
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Detail-only adapter that opens a YouTube Music album as its official importable playlist. */
class YoutubeMusicAlbumMetadataProvider(
    private val client: YoutubeExtractorClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MetadataProvider {
    override val id = "youtube-music-album-details"
    override val kind = ProviderKind.METADATA
    override val name = "YouTube Music album details"
    override val searchCapabilities: Set<SearchCapability> = emptySet()
    override val detailCapabilities = setOf(DetailCapability.ALBUM_DETAIL)
    override val ownedNamespaces = setOf(YoutubeIds.STREAMING)
    override suspend fun search(params: SearchParams) = SearchResults()

    override suspend fun albumDetail(source: ProviderRef): Album? = withContext(io) {
        if (source.provider != YoutubeIds.STREAMING || !source.id.startsWith("album:")) return@withContext null
        val listId = source.id.substringAfter(':').takeIf { it.isNotBlank() } ?: return@withContext null
        val data = client.playlist("https://music.youtube.com/playlist?list=$listId")
        val artwork = data.thumbnails.toArtworkSet()
        val title = data.name?.takeIf { it.isNotBlank() } ?: return@withContext null
        val ref = AlbumRef(title, artwork = artwork, source = source)
        val tracks = data.items.mapNotNull { it.toTrackOrNull() }
            .map { track -> track.copy(album = ref, artwork = track.artwork ?: artwork) }
        Album(title = title, artwork = artwork, tracks = tracks, totalTracks = tracks.size, source = source)
    }
}
