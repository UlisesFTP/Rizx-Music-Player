package fm.rizx.player.data.provider

import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.deezer.DeezerChartDto
import fm.rizx.player.data.remote.deezer.toAlbumRef
import fm.rizx.player.data.remote.deezer.toArtistRef
import fm.rizx.player.data.remote.deezer.toPlaylistRef
import fm.rizx.player.data.remote.deezer.toTrackOrNull
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.DashboardCapability
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.DashboardProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
    )

    private val mutex = Mutex()
    private var cache: Pair<Long, DeezerChartDto>? = null

    private suspend fun chart(): DeezerChartDto = mutex.withLock {
        cache?.takeIf { nowMs() - it.first < ttlMs }?.second
            ?: withContext(io) { api.chart() }.also { cache = nowMs() to it }
    }

    override suspend fun topTracks(limit: Int): List<Track> =
        chart().tracks.data.mapNotNull { it.toTrackOrNull() }.take(limit)

    override suspend fun topArtists(limit: Int): List<ArtistRef> =
        chart().artists.data.mapNotNull { it.toArtistRef() }.take(limit)

    override suspend fun topAlbums(limit: Int): List<AlbumRef> =
        chart().albums.data.mapNotNull { it.toAlbumRef() }.take(limit)

    override suspend fun editorialPlaylists(limit: Int): List<PlaylistRef> =
        chart().playlists.data.mapNotNull { it.toPlaylistRef() }.take(limit)

    companion object {
        const val ID = "deezer-dashboard"
    }
}
