package fm.rizx.player.data.provider

import fm.rizx.player.core.region.RegionResolver
import fm.rizx.player.data.remote.spotify.SpotifyChartIds
import fm.rizx.player.data.remote.spotify.SpotifyIds
import fm.rizx.player.domain.model.DashboardCapability
import fm.rizx.player.domain.model.PlaylistPreview
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.DashboardProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Dashboard source over Spotify's public chart playlists — "Top 50 – <country>" and "Viral 50 –
 * Global" — fetched keylessly through the same embed scrape the playlist importer already uses.
 * Contributes the charts both as playlist cards (they open through the existing preview flow, whose
 * URL rebuild already knows Spotify) and as a top-tracks row. Regional strictly behind the in-app
 * consent: without it (or with an unmapped country) only the **Global** charts are fetched. Embed
 * rows carry no per-track artwork — the blending repository borrows covers from Deezer afterwards.
 * Previews are memoized per playlist for [ttlMs]; a dead regional id degrades to Global.
 */
class SpotifyChartsDashboardProvider(
    private val playlists: SpotifyPlaylistProvider,
    private val region: RegionResolver,
    private val settings: SettingsRepository,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = 30 * 60_000L,
) : DashboardProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.DASHBOARD
    override val name: String = "Spotify Charts"
    override val dashboardCapabilities: Set<DashboardCapability> = setOf(
        DashboardCapability.TOP_TRACKS,
        DashboardCapability.EDITORIAL_PLAYLISTS,
    )

    private val mutex = Mutex()
    private val previews = mutableMapOf<String, Cached>()

    /**
     * One in-flight fetch per playlist id, shared by whoever asks for it meanwhile. The mutex used to
     * wrap the network call itself, which serialized `topTracks` behind `editorialPlaylists` — two full
     * embed pages, one after the other, on the Home's critical path. Now they overlap, and because both
     * want the same regional Top-50 page they collapse into a single download.
     */
    private val inFlight = mutableMapOf<String, CompletableDeferred<PlaylistPreview?>>()

    override suspend fun topTracks(limit: Int): List<Track> {
        val preview = regionalTopOrGlobal() ?: return emptyList()
        return preview.tracks.take(limit)
    }

    override suspend fun editorialPlaylists(limit: Int): List<PlaylistRef> = coroutineScope {
        val topId = SpotifyChartIds.top50(consentedCountry())
        val ids = listOf(topId, SpotifyChartIds.VIRAL_50_GLOBAL).distinct()
        ids.map { pid -> async { preview(pid)?.toRef(pid) } }.awaitAll().filterNotNull().take(limit)
    }

    private suspend fun regionalTopOrGlobal(): PlaylistPreview? {
        val topId = SpotifyChartIds.top50(consentedCountry())
        return preview(topId)
            ?: SpotifyChartIds.TOP_50_GLOBAL.takeIf { it != topId }?.let { preview(it) }
    }

    private suspend fun consentedCountry(): String? =
        region.country().takeIf { settings.recsRegionalConsent.first() == true }

    /**
     * Memoized, request-collapsing embed fetch. The lock only ever guards the maps — never the network
     * call. Failures aren't cached: the next Home load simply retries.
     */
    private suspend fun preview(playlistId: String): PlaylistPreview? {
        val pending = mutex.withLock {
            previews[playlistId]?.takeIf { nowMs() - it.atMs < ttlMs }?.let { return it.preview }
            inFlight[playlistId]?.let { return@withLock it to false }
            CompletableDeferred<PlaylistPreview?>().also { inFlight[playlistId] = it } to true
        }
        val (deferred, isLeader) = pending
        if (!isLeader) return deferred.await()

        val fetched = try {
            playlists.fetchPlaylist(PLAYLIST_URL + playlistId)
        } catch (e: CancellationException) {
            mutex.withLock { inFlight.remove(playlistId) }
            deferred.complete(null) // the followers must not inherit this leader's cancellation
            throw e
        } catch (_: Exception) {
            null
        }
        mutex.withLock {
            if (fetched != null) previews[playlistId] = Cached(nowMs(), fetched)
            inFlight.remove(playlistId)
        }
        deferred.complete(fetched)
        return fetched
    }

    private fun PlaylistPreview.toRef(playlistId: String) = PlaylistRef(
        id = playlistId,
        name = name,
        artwork = artwork,
        source = SpotifyIds.playlist(playlistId),
        trackCount = tracks.size,
    )

    private class Cached(val atMs: Long, val preview: PlaylistPreview)

    companion object {
        const val ID = "spotify-charts"
        private const val PLAYLIST_URL = "https://open.spotify.com/playlist/"
    }
}
