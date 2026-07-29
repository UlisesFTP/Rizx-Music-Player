package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.applemusic.AppleMusicIds
import fm.rizx.player.data.remote.applemusic.appleArtistDetail
import fm.rizx.player.data.remote.applemusic.toAppleAlbum
import fm.rizx.player.data.remote.applemusic.toAppleAlbumRefOrNull
import fm.rizx.player.data.remote.applemusic.toAppleArtistOrNull
import fm.rizx.player.data.remote.applemusic.toAppleSearchResults
import fm.rizx.player.data.remote.applemusic.toAppleTrackOrNull
import fm.rizx.player.data.remote.itunes.ItunesApi
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The **Apple Music** catalogue as a selectable metadata source, over the public, documented, keyless
 * iTunes Search API (ADR 0018).
 *
 * There is no Apple web-player token anywhere in this app: `search` with `entity` set covers the
 * songs/artists/albums tabs, and `lookup` pivots an id into its children for detail — artist → top
 * songs + albums, album → track list. That is the whole surface, and it needs no credential.
 *
 * Audio is somebody else's job: like Deezer, this provider only describes music, and the streaming
 * chain resolves playback by artist + title.
 */
class AppleMusicMetadataProvider(
    private val api: ItunesApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MetadataProvider {

    override val id: String = AppleMusicIds.METADATA
    override val kind: ProviderKind = ProviderKind.METADATA
    override val name: String = "Apple Music"
    override val version: String = "1.0"
    override val searchCapabilities: Set<SearchCapability> =
        setOf(SearchCapability.UNIFIED, SearchCapability.ARTISTS, SearchCapability.ALBUMS, SearchCapability.TRACKS)
    override val detailCapabilities: Set<DetailCapability> =
        setOf(DetailCapability.ALBUM_DETAIL, DetailCapability.ARTIST_DETAIL)

    /** Refs are minted in the catalogue namespace (`applemusic`), not under this provider's id. */
    override val ownedNamespaces: Set<String> = setOf(AppleMusicIds.PROVIDER)

    /**
     * Exact lookup by Apple's own track id — the "go back to the owner" path. One request returns the
     * real title, artist, album and square cover, so nothing has to be matched and the answer cannot
     * be a different song. Sub-namespaced refs (`artist:`/`album:`) are not tracks and yield null.
     */
    override suspend fun trackDetail(source: ProviderRef): Track? {
        if (!owns(source) || ':' in source.id) return null
        val trackId = source.id.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: return null
        return guarded {
            withContext(io) {
                api.lookup(id = trackId, entity = "song", limit = 1).results
                    .firstNotNullOfOrNull { it.toAppleTrackOrNull() }
            }
        }
    }

    /**
     * The same lookup for many ids at once. iTunes accepts a comma-separated list, so a 50-track
     * playlist costs one request instead of fifty — which is what makes hydrating an editorial
     * playlist (whose rows carry ids but no artist) cheap enough to do on open.
     */
    suspend fun trackDetails(ids: List<String>): List<Track> {
        val clean = ids.filter { it.isNotBlank() && it.all(Char::isDigit) }.distinct()
        if (clean.isEmpty()) return emptyList()
        return guarded {
            withContext(io) {
                clean.chunked(LOOKUP_BATCH).flatMap { batch ->
                    runCatching {
                        api.lookup(id = batch.joinToString(","), entity = "song", limit = batch.size * 2)
                            .results.mapNotNull { it.toAppleTrackOrNull() }
                    }.getOrDefault(emptyList())
                }
            }
        }
    }

    override suspend fun search(params: SearchParams): SearchResults {
        val query = params.query.trim()
        if (query.isEmpty()) return SearchResults()
        val limit = params.limit ?: DEFAULT_LIMIT
        return guarded {
            withContext(io) {
                when {
                    // A typed tab asks iTunes for that entity directly — far better rows than
                    // deriving artists/albums from whatever songs happened to match.
                    params.types?.singleOrNull() == SearchCategory.ARTISTS ->
                        SearchResults(
                            artists = api.search(term = query, entity = "musicArtist", limit = limit)
                                .results.mapNotNull { it.toAppleArtistOrNull() },
                        )
                    params.types?.singleOrNull() == SearchCategory.ALBUMS ->
                        SearchResults(
                            albums = api.search(term = query, entity = "album", limit = limit)
                                .results.mapNotNull { it.toAppleAlbumRefOrNull() },
                        )
                    else -> api.search(term = query, entity = "song", limit = limit).results.toAppleSearchResults()
                }
            }
        }
    }

    override suspend fun albumDetail(source: ProviderRef): Album? {
        val albumId = AppleMusicIds.idOf(source, "album") ?: return null
        return guarded {
            withContext(io) {
                api.lookup(id = albumId, entity = "song", limit = ALBUM_TRACK_LIMIT).results.toAppleAlbum(source)
            }
        }
    }

    override suspend fun artistDetail(source: ProviderRef): Artist? {
        val artistId = AppleMusicIds.idOf(source, "artist") ?: return null
        return guarded {
            withContext(io) {
                coroutineScope {
                    // Two independent lookups — run them together; the detail screen waits for both.
                    val songs = async { runCatching { api.lookup(artistId, entity = "song", limit = TOP_TRACK_LIMIT).results }.getOrDefault(emptyList()) }
                    val albums = async { runCatching { api.lookup(artistId, entity = "album", limit = ALBUM_LIMIT).results }.getOrDefault(emptyList()) }
                    // The first row of a lookup is the looked-up entity itself, not a child.
                    appleArtistDetail(
                        source = source,
                        songRows = songs.await().filter { it.trackId != null },
                        albumRows = albums.await().filter { it.collectionId != null },
                    )
                }
            }
        }
    }

    /**
     * Genre radio: Apple exposes no "similar artists" without a developer key, but every row carries
     * its `primaryGenreName`, and a genre search is a real signal rather than a guess. Seeds with the
     * artist's own catalogue first so the mix opens on something recognisable.
     */
    override suspend fun radioTracks(seed: Track): List<Track> {
        val artistName = seed.artists.firstOrNull()?.name ?: return emptyList()
        return guarded {
            withContext(io) {
                val artistId = AppleMusicIds.idOf(seed.artists.firstOrNull()?.source ?: ProviderRef("", ""), "artist")
                    ?: api.search(term = artistName, entity = "musicArtist", limit = 1).results.firstOrNull()?.artistId?.toString()
                val own = artistId
                    ?.let { runCatching { api.lookup(it, entity = "song", limit = RADIO_OWN_LIMIT).results }.getOrDefault(emptyList()) }
                    ?.filter { it.trackId != null }
                    ?.mapNotNull { it.toAppleTrackOrNull() }
                    .orEmpty()
                val genre = seed.tags.firstOrNull() ?: own.firstNotNullOfOrNull { it.tags.firstOrNull() }
                val sameGenre = genre
                    ?.let {
                        runCatching {
                            api.search(term = it, entity = "song", limit = RADIO_GENRE_LIMIT, attribute = "genreTerm").results
                        }.getOrDefault(emptyList())
                    }
                    ?.mapNotNull { it.toAppleTrackOrNull() }
                    .orEmpty()
                (own + sameGenre).distinctBy { it.source }.filter { it.source != seed.source }
            }
        }.orEmpty()
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw AppError.Network(e.message ?: "connection failed", e)
    } catch (e: Exception) {
        throw AppError.ProviderFailure(name, e.message ?: "Apple Music request failed", e)
    }

    private companion object {
        const val DEFAULT_LIMIT = 25
        const val ALBUM_TRACK_LIMIT = 60
        const val TOP_TRACK_LIMIT = 15
        const val ALBUM_LIMIT = 25
        const val RADIO_OWN_LIMIT = 10
        const val RADIO_GENRE_LIMIT = 25

        /** iTunes tolerates long id lists, but a bounded batch keeps one bad page from losing them all. */
        const val LOOKUP_BATCH = 50
    }
}
