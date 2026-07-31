package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.deezer.DeezerIds
import fm.rizx.player.data.remote.deezer.DeezerArtistSearch
import fm.rizx.player.data.remote.deezer.allPlaylistTracks
import fm.rizx.player.data.remote.deezer.toAlbum
import fm.rizx.player.data.remote.deezer.toAlbumRef
import fm.rizx.player.data.remote.deezer.toArtist
import fm.rizx.player.data.remote.deezer.toArtistRef
import fm.rizx.player.data.remote.deezer.toSearchResults
import fm.rizx.player.data.remote.deezer.toTrackOrNull
import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.ArtistRef
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
    /** Shared so this provider's artist search and its radio's id lookup don't ask Deezer twice. */
    private val artists: DeezerArtistSearch = DeezerArtistSearch(api),
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
                    SearchResults(artists = artists.byName(query, limit).mapNotNull { it.toArtistRef() })
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
        header.toArtist(topTracks = top, albums = allAlbums(artistId))
    }

    /**
     * The artist's **whole** discography, paged.
     *
     * One page used to be the discography, which for anyone prolific meant twenty of their eighty
     * releases and no way to reach the rest. Paging stops as soon as a page comes back short — that is
     * the end of the list — and at [MAX_ALBUMS], which is a backstop against a catalogue that keeps
     * answering rather than a real limit anyone reaches.
     */
    private suspend fun allAlbums(artistId: String): List<AlbumRef> {
        val out = ArrayList<AlbumRef>(ALBUM_PAGE)
        var index = 0
        while (index < MAX_ALBUMS) {
            val page = api.artistAlbums(artistId, ALBUM_PAGE, index).data
            out += page.mapNotNull { it.toAlbumRef() }
            if (page.size < ALBUM_PAGE) break
            index += ALBUM_PAGE
        }
        return out.distinctBy { it.source }
    }

    /**
     * Artists Deezer considers similar. Already the same endpoint behind the Home's "Artists for you"
     * row — the artist page just asks it about the artist you are looking at.
     */
    override suspend fun relatedArtists(source: ProviderRef): List<ArtistRef> = guarded {
        api.artistRelated(DeezerIds.rawId(source), RELATED_LIMIT).data.mapNotNull { it.toArtistRef() }
    }

    /**
     * Deezer's artist radio — ~25 tracks "in the style of" the artist, mixing in related acts, which is
     * a real recommendation flow rather than a discography listing.
     *
     * It is keyed by **artist id**, so a seed from another provider used to skip it entirely and fall
     * back to a plain track search by name, shuffled — text matching dressed up as a radio, and for a
     * YouTube seed ("ModjoOfficial") a search that returns nothing, leaving "next" with no tracks at
     * all. Now the id is looked up first ([findArtistId], which reads a channel name as the artist
     * behind it), so the real radio is used whatever the seed came from. The old search stays as the
     * last resort for an artist Deezer genuinely doesn't have.
     */
    override suspend fun radioTracks(seed: Track): List<Track> = guarded {
        val credit = seed.artists.firstOrNull()
        val artistId = artists.idFor(credit)
        if (artistId != null) {
            api.artistRadio(artistId, RADIO_LIMIT).data.mapNotNull { it.toTrackOrNull() }
        } else {
            val artistName = credit?.name?.trim().orEmpty()
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

        /** The artist page's song list. Deep enough to be "their songs" rather than a teaser. */
        private const val TOP_LIMIT = 50
        private const val ALBUM_PAGE = 50
        private const val MAX_ALBUMS = 200
        private const val RADIO_LIMIT = 25
        private const val RELATED_LIMIT = 12
    }
}
