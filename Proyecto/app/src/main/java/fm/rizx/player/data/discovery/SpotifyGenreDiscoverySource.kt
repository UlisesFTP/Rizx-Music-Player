package fm.rizx.player.data.discovery

import fm.rizx.player.data.provider.SpotifyPlaylistProvider
import fm.rizx.player.data.remote.spotify.SpotifyIds
import fm.rizx.player.domain.discovery.DiscoveryPlaylistSource
import fm.rizx.player.domain.discovery.GenreCatalog
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.SoundGenre
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/**
 * Spotify's part of the genre shelves: the editorial playlists [GenreCatalog] lists for a genre, each
 * read through the public embed for its real name, cover and length.
 *
 * Genre only. Spotify has no keyless way to ask which playlists feature an artist — its search needs the
 * TOTP-minted bearer this project refuses (ADR 0018) — so `forArtist` keeps the interface's empty
 * answer. An id Spotify has since retired fails its own embed and is simply missing from the shelf.
 */
class SpotifyGenreDiscoverySource(
    private val playlists: SpotifyPlaylistProvider,
    private val memo: DiscoveryMemo<List<PlaylistRef>> = DiscoveryMemo(empty = emptyList()),
) : DiscoveryPlaylistSource {

    override val id: String = "spotify"

    override suspend fun forGenre(genre: SoundGenre): List<PlaylistRef> {
        val ids = GenreCatalog.entry(genre)?.spotifyPlaylistIds.orEmpty()
        if (ids.isEmpty()) return emptyList()
        return supervisorScope {
            ids.map { pid -> async { one(pid) } }.awaitAll().filterNotNull()
        }
    }

    private suspend fun one(playlistId: String): PlaylistRef? =
        memo.get(playlistId, List<PlaylistRef>::isEmpty) {
            val preview = playlists.fetchPlaylistForDashboard(PLAYLIST_URL + playlistId)
            listOf(
                PlaylistRef(
                    id = playlistId,
                    name = preview.name,
                    artwork = preview.artwork,
                    source = SpotifyIds.playlist(playlistId),
                    trackCount = preview.tracks.size,
                ),
            )
        }.firstOrNull()

    private companion object {
        const val PLAYLIST_URL = "https://open.spotify.com/playlist/"
    }
}
