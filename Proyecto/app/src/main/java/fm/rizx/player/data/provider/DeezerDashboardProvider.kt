package fm.rizx.player.data.provider

import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.deezer.DeezerChartDto
import fm.rizx.player.data.remote.deezer.DeezerIds
import fm.rizx.player.data.remote.deezer.toAlbumRef
import fm.rizx.player.data.remote.deezer.toArtistRef
import fm.rizx.player.data.remote.deezer.toMoodStation
import fm.rizx.player.data.remote.deezer.toPlaylistRef
import fm.rizx.player.data.remote.deezer.toTrackOrNull
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.DashboardCapability
import fm.rizx.player.domain.model.FeaturedPlaylist
import fm.rizx.player.domain.model.GenreFeed
import fm.rizx.player.domain.model.MoodStation
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.DashboardProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Dashboard provider backed by Deezer's keyless `/chart` (Phase 19): top tracks/albums/artists +
 * editorial playlists, all from **one** call. The chart response is memoized for [ttlMs] so the
 * repository's per-section fan-out collapses to a single network fetch. Failures propagate to the
 * repository, which isolates them per section.
 */
class DeezerDashboardProvider(
    private val api: DeezerApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = 60_000,
) : DashboardProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.DASHBOARD
    override val name: String = "Deezer Charts"
    override val dashboardCapabilities: Set<DashboardCapability> = setOf(
        DashboardCapability.TOP_TRACKS,
        DashboardCapability.TOP_ARTISTS,
        DashboardCapability.TOP_ALBUMS,
        DashboardCapability.EDITORIAL_PLAYLISTS,
        DashboardCapability.MOOD_STATIONS,
        DashboardCapability.FEATURED_PLAYLISTS,
        DashboardCapability.GENRE_FEED,
    )

    private val mutex = Mutex()
    private var cache: Pair<Long, DeezerChartDto>? = null

    /** Per-genre charts, same TTL as the global one, oldest evicted past [GENRE_CACHE_ENTRIES]. */
    private val genreCache = LinkedHashMap<String, Pair<Long, DeezerChartDto>>()

    private suspend fun chart(): DeezerChartDto = mutex.withLock {
        cache?.takeIf { nowMs() - it.first < ttlMs }?.second
            ?: withContext(io) { api.chart() }.also { cache = nowMs() to it }
    }

    private suspend fun genreChart(genreId: String, limit: Int): DeezerChartDto = mutex.withLock {
        val key = "$genreId/$limit"
        genreCache[key]?.takeIf { nowMs() - it.first < ttlMs }?.second
            ?: withContext(io) { api.genreChart(genreId, limit) }.also {
                genreCache[key] = nowMs() to it
                if (genreCache.size > GENRE_CACHE_ENTRIES) {
                    genreCache.keys.firstOrNull()?.let(genreCache::remove)
                }
            }
    }

    override suspend fun topTracks(limit: Int): List<Track> =
        chart().tracks.data.mapNotNull { it.toTrackOrNull() }.take(limit)

    override suspend fun topArtists(limit: Int): List<ArtistRef> =
        chart().artists.data.mapNotNull { it.toArtistRef() }.take(limit)

    override suspend fun topAlbums(limit: Int): List<AlbumRef> =
        chart().albums.data.mapNotNull { it.toAlbumRef() }.take(limit)

    override suspend fun editorialPlaylists(limit: Int): List<PlaylistRef> =
        chart().playlists.data.mapNotNull { it.toPlaylistRef() }.take(limit)

    /**
     * The first chart playlists promoted to full cards, each with a track peek (one extra call per
     * card, fetched concurrently). A card that could not get its peek is dropped — its whole point is
     * showing what's inside, and the plain carousel below still offers the playlist itself.
     */
    override suspend fun featuredPlaylists(limit: Int): List<FeaturedPlaylist> = supervisorScope {
        chart().playlists.data.mapNotNull { it.toPlaylistRef() }.take(limit)
            .map { ref ->
                async {
                    val peek = runCatching {
                        withContext(io) { api.playlistTracks(DeezerIds.rawId(ref.source), index = 0, limit = PREVIEW_TRACKS) }
                            .data.mapNotNull { it.toTrackOrNull() }
                    }.getOrDefault(emptyList())
                    FeaturedPlaylist(playlist = ref, preview = peek)
                }
            }
            .awaitAll()
            .filter { it.preview.isNotEmpty() }
    }

    /**
     * Deezer's curated stations, deduped by title — `/radio/lists` legitimately repeats names across
     * regional editions ("Hits" twice, verified), and two chips with one label read as a bug.
     */
    override suspend fun moodStations(limit: Int): List<MoodStation> =
        withContext(io) { api.radioLists(RADIO_FETCH) }.data
            .mapNotNull { it.toMoodStation() }
            .distinctBy { it.title.lowercase() }
            .take(limit)

    override suspend fun stationTracks(stationId: String, limit: Int): List<Track> =
        withContext(io) { api.radioTracks(stationId, limit) }.data.mapNotNull { it.toTrackOrNull() }

    /**
     * One genre's chart. [genreId] is a Deezer genre id (`132` Pop, `464` Metal, `0` everything);
     * anything else is refused here rather than pasted into a URL.
     */
    override suspend fun genreFeed(genreId: String, limit: Int): GenreFeed {
        val id = genreId.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return GenreFeed()
        val chart = genreChart(id, limit)
        return GenreFeed(
            tracks = chart.tracks.data.mapNotNull { it.toTrackOrNull() },
            playlists = chart.playlists.data.mapNotNull { it.toPlaylistRef() },
            artists = artistsOf(chart),
            albums = chart.albums.data.mapNotNull { it.toAlbumRef() },
        )
    }

    /**
     * The genre's artists, read off **its own tracks and albums**.
     *
     * Pointedly not `chart.artists`: that section ignores the genre entirely. Metal, Jazz, Classical and
     * an id that does not exist at all answer with the same regional top-artist list (verified live
     * against the API), so shipping it would have put Bad Bunny at the top of Metal. Rows that *are*
     * genre-filtered give AC/DC, Metallica and Rammstein instead.
     *
     * Deduped by [ProviderRef] — the same artist charting with three songs is one artist.
     */
    private fun artistsOf(chart: DeezerChartDto): List<ArtistRef> {
        val byRef = LinkedHashMap<ProviderRef, ArtistRef>()
        chart.tracks.data.forEach { track ->
            track.artist?.toArtistRef()?.let { byRef.putIfAbsent(it.source, it) }
        }
        chart.albums.data.forEach { album ->
            album.artist?.toArtistRef()?.let { byRef.putIfAbsent(it.source, it) }
        }
        return byRef.values.toList()
    }

    companion object {
        const val ID = "deezer-dashboard"

        /** A user browsing genres bounces between a handful; more than this and it isn't a browse. */
        private const val GENRE_CACHE_ENTRIES = 8

        /** What a featured card actually draws; playing the playlist re-fetches the real list. */
        private const val PREVIEW_TRACKS = 4

        /** Fetched deeper than shown because the title dedupe eats a few. */
        private const val RADIO_FETCH = 40
    }
}
