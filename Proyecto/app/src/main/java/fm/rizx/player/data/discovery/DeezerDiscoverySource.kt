package fm.rizx.player.data.discovery

import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.deezer.DeezerPlaylistDto
import fm.rizx.player.data.remote.deezer.toPlaylistRef
import fm.rizx.player.domain.discovery.DiscoveryAnchor
import fm.rizx.player.domain.discovery.DiscoveryPlaylistSource
import fm.rizx.player.domain.discovery.GenreCatalog
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.SoundGenre
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deezer's editorial playlists for an artist and for a genre — keyless (`/artist/{id}/playlists`,
 * `/chart/{genreId}/playlists`).
 *
 * **Only Deezer's own playlists are kept.** Measured 2026-11-02 on Bad Bunny: the artist list mixes
 * Deezer editors ("Camojada - Deezer Latin Editor", "Deezer Artist Editor") with a label's SEO accounts
 * ("Filtr Mexico") and spam ("REGETON 2026 REAGGETON, REGAETON…"). The creator name is the one signal
 * that separates them, so anything not made by a "Deezer …" account is dropped. Within what remains the
 * artist's own "100% <artist>" leads, then the editors, then the country charts (the Home already shows
 * charts).
 */
class DeezerDiscoverySource(
    private val api: DeezerApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val memo: DiscoveryMemo<List<PlaylistRef>> = DiscoveryMemo(empty = emptyList()),
) : DiscoveryPlaylistSource {

    override val id: String = "deezer"

    override suspend fun forArtist(anchor: DiscoveryAnchor): List<PlaylistRef> {
        val artistId = anchor.deezerId?.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: return emptyList()
        return memo.get("artist:$artistId", List<PlaylistRef>::isEmpty) {
            withContext(io) { api.artistPlaylists(artistId, FETCH) }.data
                .filter { it.isDeezerEditorial() }
                .sortedBy { rankOf(it, anchor.name) }
                .mapNotNull { it.toPlaylistRef() }
        }
    }

    override suspend fun forGenre(genre: SoundGenre): List<PlaylistRef> {
        val genreId = GenreCatalog.entry(genre)?.deezerGenreId ?: return emptyList()
        return memo.get("genre:$genreId", List<PlaylistRef>::isEmpty) {
            withContext(io) { api.genrePlaylists(genreId.toString(), FETCH) }.data
                // Genre charts are Deezer's by construction, but the same rule keeps the source honest
                // if Deezer ever starts mixing partner lists in here too.
                .filter { it.user?.name == null || it.isDeezerEditorial() }
                .mapNotNull { it.toPlaylistRef() }
        }
    }

    private fun DeezerPlaylistDto.isDeezerEditorial(): Boolean =
        user?.name?.contains(DEEZER, ignoreCase = true) == true

    /** 0 = the artist's own "100% <artist>", 1 = an editor's list, 2 = a country chart. */
    private fun rankOf(playlist: DeezerPlaylistDto, artistName: String): Int {
        val creator = playlist.user?.name.orEmpty()
        val title = playlist.title.orEmpty()
        return when {
            creator.contains(ARTIST_EDITOR, ignoreCase = true) && title.contains(artistName, ignoreCase = true) -> 0
            creator.contains(CHARTS, ignoreCase = true) -> 2
            else -> 1
        }
    }

    private companion object {
        /** One page — the editors' lists come first and the tail is mostly other accounts. */
        const val FETCH = 50
        const val DEEZER = "Deezer"
        const val ARTIST_EDITOR = "Artist Editor"
        const val CHARTS = "Deezer Charts"
    }
}
