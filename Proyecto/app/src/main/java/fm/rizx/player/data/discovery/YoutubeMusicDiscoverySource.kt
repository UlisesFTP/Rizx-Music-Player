package fm.rizx.player.data.discovery

import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.toPlaylistRefOrNull
import fm.rizx.player.domain.discovery.DiscoveryAnchor
import fm.rizx.player.domain.discovery.DiscoveryPlaylistSource
import fm.rizx.player.domain.discovery.GenreCatalog
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.SoundGenre
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem

/**
 * YouTube Music's playlists for an artist or a genre, through the NewPipe `music_playlists` search the
 * app already uses for the Home's YouTube Music albums (keyless, ADR 0018).
 *
 * A search, so what comes back is whatever YouTube Music ranks — its own editorial lists mixed with
 * listeners'. YouTube Music's own (uploader "YouTube Music") are moved to the front; the rest are kept,
 * because for many artists the community list *is* the good one, and the row's source badge says
 * where each card came from.
 */
class YoutubeMusicDiscoverySource(
    private val client: YoutubeExtractorClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val memo: DiscoveryMemo<List<PlaylistRef>> = DiscoveryMemo(empty = emptyList()),
) : DiscoveryPlaylistSource {

    override val id: String = "youtube-music"

    override suspend fun forArtist(anchor: DiscoveryAnchor): List<PlaylistRef> {
        val name = anchor.name.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        return search(name)
    }

    override suspend fun forGenre(genre: SoundGenre): List<PlaylistRef> {
        val query = GenreCatalog.entry(genre)?.youtubeQuery ?: return emptyList()
        return search(query)
    }

    private suspend fun search(query: String): List<PlaylistRef> =
        memo.get("q:${query.lowercase()}", List<PlaylistRef>::isEmpty) {
            withContext(io) { client.searchMusicPlaylists(query, LIMIT) }
                .sortedByDescending { it.isOfficial() }
                .mapNotNull { it.toPlaylistRefOrNull() }
        }

    private fun PlaylistInfoItem.isOfficial(): Boolean =
        uploaderName?.equals(OFFICIAL_UPLOADER, ignoreCase = true) == true

    private companion object {
        const val LIMIT = 12
        const val OFFICIAL_UPLOADER = "YouTube Music"
    }
}
