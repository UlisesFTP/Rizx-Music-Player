package fm.rizx.player.data.discovery

import fm.rizx.player.data.remote.applemusic.AppleMusicShelfPage
import fm.rizx.player.data.remote.itunes.ItunesApi
import fm.rizx.player.domain.discovery.DiscoveryAnchor
import fm.rizx.player.domain.discovery.DiscoveryPlaylistSource
import fm.rizx.player.domain.discovery.GenreCatalog
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.SoundGenre
import fm.rizx.player.domain.usecase.ArtistNameMatching
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Apple Music's editorial playlists for an artist (its "Essentials", "Deep Cuts" and set lists) and for a
 * genre (the genre curator's shelf), read from the public pages ([AppleMusicShelfPage]).
 *
 * - **Artist id** comes from the public iTunes Search API (`entity=musicArtist`), matched by name key so
 *   a same-named stranger with three fans is not picked — the first row is not always the artist.
 * - **Storefront** is the listener's country only with the regional consent, otherwise [DEFAULT_STOREFRONT]
 *   (the same rule as every chart source). A curator missing from that storefront falls back to it too.
 * - **Data saver skips this source entirely.** These pages weigh 0.8–1.8 MB of HTML, by far the heaviest
 *   discovery request; the other three platforms still fill the shelf.
 */
class AppleMusicDiscoverySource(
    private val itunes: ItunesApi,
    private val pages: AppleMusicShelfPage,
    private val storefront: suspend () -> String,
    private val savingData: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val memo: DiscoveryMemo<List<PlaylistRef>> = DiscoveryMemo(empty = emptyList()),
    private val ids: DiscoveryMemo<String> = DiscoveryMemo(empty = ""),
) : DiscoveryPlaylistSource {

    override val id: String = "applemusic"

    override suspend fun forArtist(anchor: DiscoveryAnchor): List<PlaylistRef> {
        if (savingData()) return emptyList()
        val front = storefront()
        val artistId = ids.get("$front|${ArtistNameMatching.key(anchor.name)}", String::isEmpty) {
            artistId(anchor.name, front)
        }.ifEmpty { return emptyList() }
        return memo.get("artist:$front:$artistId", List<PlaylistRef>::isEmpty) {
            withContext(io) { pages.artistPlaylists(front, artistId) }
        }
    }

    override suspend fun forGenre(genre: SoundGenre): List<PlaylistRef> {
        if (savingData()) return emptyList()
        val curator = GenreCatalog.entry(genre)?.appleCurator ?: return emptyList()
        val front = storefront()
        return memo.get("curator:$front:${curator.id}", List<PlaylistRef>::isEmpty) {
            withContext(io) {
                pages.curatorPlaylists(front, curator.id).ifEmpty {
                    if (front == DEFAULT_STOREFRONT) emptyList() else pages.curatorPlaylists(DEFAULT_STOREFRONT, curator.id)
                }
            }
        }
    }

    private suspend fun artistId(name: String, front: String): String {
        val wanted = ArtistNameMatching.key(name)
        if (wanted.isEmpty()) return ""
        val rows = withContext(io) { itunes.search(term = name, entity = "musicArtist", limit = SEARCH_LIMIT, country = front) }.results
        return rows.firstOrNull { row -> row.artistName?.let(ArtistNameMatching::key) == wanted }
            ?.artistId?.toString().orEmpty()
    }

    companion object {
        const val DEFAULT_STOREFRONT = "us"
        private const val SEARCH_LIMIT = 5
    }
}
