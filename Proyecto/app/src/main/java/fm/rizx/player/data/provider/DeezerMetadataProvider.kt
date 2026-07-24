package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.deezer.DeezerIds
import fm.rizx.player.data.remote.deezer.allPlaylistTracks
import fm.rizx.player.data.remote.deezer.toAlbum
import fm.rizx.player.data.remote.deezer.toAlbumRef
import fm.rizx.player.data.remote.deezer.toArtist
import fm.rizx.player.data.remote.deezer.toArtistRef
import fm.rizx.player.data.remote.deezer.toSearchResults
import fm.rizx.player.data.remote.deezer.toTrackOrNull
import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.DetailCapability
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.model.SearchCategory
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Real metadata provider backed by the keyless Deezer public API (Phase 17). Unified search plus
 * **album/artist detail** (`albumDetail`/`artistDetail`) — the first provider to fill the full
 * [Album]/[Artist] models. Network I/O on [io]; failures wrapped in [AppError]; DTOs never escape the
 * data layer.
 */
class DeezerMetadataProvider(
    private val api: DeezerApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MetadataProvider {

    override val id: String = DeezerIds.PROVIDER
    override val kind: ProviderKind = ProviderKind.METADATA
    override val name: String = "Deezer"
    override val searchCapabilities: Set<SearchCapability> =
        setOf(SearchCapability.UNIFIED, SearchCapability.ARTISTS, SearchCapability.ALBUMS)
    override val detailCapabilities: Set<DetailCapability> =
        setOf(DetailCapability.ALBUM_DETAIL, DetailCapability.ARTIST_DETAIL)

    /**
     * Honors [SearchParams.types]: an artists-only or albums-only request hits Deezer's **dedicated**
     * `search/artist`/`search/album` index (ranked, complete — for the Artists/Albums tabs), while the
     * default (tracks, or unified) uses the generic track search and derives artist/album refs from it.
     * Playlist search is served separately (multi-source), not here.
     */
    override suspend fun search(params: SearchParams): SearchResults {
        val query = params.query.trim()
        if (query.isEmpty()) return SearchResults()
        val limit = params.limit ?: DEFAULT_LIMIT
        return guarded {
            when (params.types) {
                listOf(SearchCategory.ARTISTS) ->
                    SearchResults(artists = api.searchArtists(query, limit).data.mapNotNull { it.toArtistRef() })
                listOf(SearchCategory.ALBUMS) ->
                    SearchResults(albums = api.searchAlbums(query, limit).data.mapNotNull { it.toAlbumRef() })
                else ->
                    api.searchTracks(query, limit).data.toSearchResults()
            }
        }
    }

    override suspend fun albumDetail(source: ProviderRef): Album? = guarded {
        api.album(DeezerIds.rawId(source)).toAlbum()
    }

    override suspend fun artistDetail(source: ProviderRef): Artist? = guarded {
        val artistId = DeezerIds.rawId(source)
        val header = api.artist(artistId)
        val top = api.artistTop(artistId, TOP_LIMIT).data.mapNotNull { it.toTrackOrNull() }
        val albums = api.artistAlbums(artistId, ALBUM_LIMIT).data.mapNotNull { it.toAlbumRef() }
        header.toArtist(topTracks = top, albums = albums)
    }

    override suspend fun radioTracks(seed: Track): List<Track> = guarded {
        val artistSource = seed.artists.firstOrNull()?.source
        if (artistSource != null && artistSource.provider == DeezerIds.PROVIDER) {
            // The seed is a Deezer track — Deezer's artist "radio" is a real similar-tracks flow.
            api.artistRadio(DeezerIds.rawId(artistSource), RADIO_LIMIT).data.mapNotNull { it.toTrackOrNull() }
        } else {
            // Seed from another provider (e.g. iTunes) — approximate by searching Deezer for the artist.
            val artistName = seed.artists.firstOrNull()?.name?.trim().orEmpty()
            if (artistName.isEmpty()) emptyList()
            else api.searchTracks(artistName, RADIO_LIMIT).data.mapNotNull { it.toTrackOrNull() }.shuffled()
        }
    }

    override suspend fun playlistTracks(source: ProviderRef): List<Track> = guarded {
        val id = DeezerIds.rawId(source)
        val dto = api.playlist(id)
        // Same 400-track truncation as the URL import: an editorial playlist opened from Home has to
        // show all of its songs, not the first page.
        api.allPlaylistTracks(id, dto.tracks?.data.orEmpty(), dto.nbTracks)
            .mapNotNull { it.toTrackOrNull() }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        withContext(io) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw AppError.Network(e.message ?: "connection failed", e)
    } catch (e: Exception) {
        throw AppError.ProviderFailure(name, e.message ?: "deezer request failed", e)
    }

    companion object {
        private const val DEFAULT_LIMIT = 25
        private const val TOP_LIMIT = 15
        private const val ALBUM_LIMIT = 20
        private const val RADIO_LIMIT = 25
    }
}
