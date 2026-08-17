package fm.rizx.player.data.provider

import fm.rizx.player.core.region.RegionResolver
import fm.rizx.player.data.remote.youtube.YoutubeChartsClient
import fm.rizx.player.data.remote.youtube.YoutubeChartsPage
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.toMusicAlbumRefOrNull
import fm.rizx.player.data.remote.youtube.toPlaylistRefOrNull
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.DashboardCapability
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.provider.DashboardProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Dashboard source over **YouTube Music's charts** (`charts.youtube.com`), keyless — see
 * [fm.rizx.player.data.remote.youtube.YoutubeChartsResponse] for the evidence and the ADR 0018 reading.
 *
 * Contributes the country's top songs and top artists. Regional strictly behind the in-app consent:
 * without it the **global** chart is fetched, which reveals nothing about the listener — the same rule
 * the Spotify and Apple chart sources already follow.
 *
 * Two capabilities only, and both omissions are deliberate:
 * - the response's `videos` list duplicates the song chart and carries video stills, which would drag
 *   the artwork pipeline backwards for no new content;
 * - **no `MOOD_STATIONS`**. A second source of that capability would collapse station attribution to
 *   `"blended"` in `BlendingDashboardRepository`, and `stationTracks` looks a station up *by* provider
 *   id — every mood tile in the app would go dead. That fault is latent today; this must not wake it.
 *
 * Charted rows carry the chart's own thumbnail, which is a small YouTube image rather than the release
 * cover. Upgrading it is `BlendingDashboardRepository`'s job, not this one's: the enricher walks the
 * provider registry, so a provider that held one would be a dependency cycle. That repository already
 * enriches the feed's visible head and now does it with `upgradeFrom = youtube` for exactly this.
 */
class YoutubeChartsDashboardProvider(
    private val client: YoutubeChartsClient,
    private val region: RegionResolver,
    private val settings: SettingsRepository,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = 30 * 60_000L,
    private val extractor: YoutubeExtractorClient? = null,
) : DashboardProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.DASHBOARD
    override val name: String = "YouTube Music Charts"
    override val dashboardCapabilities: Set<DashboardCapability> = setOf(
        DashboardCapability.TOP_TRACKS,
        DashboardCapability.TOP_ARTISTS,
        DashboardCapability.TOP_ALBUMS,
        DashboardCapability.EDITORIAL_PLAYLISTS,
    )

    private val mutex = Mutex()
    private var cached: Cached? = null
    private var albumsCached: CachedItems<AlbumRef>? = null
    private var playlistsCached: CachedItems<PlaylistRef>? = null

    /** One in-flight fetch shared by whoever asks meanwhile — the two rows want the same page. */
    private var inFlight: CompletableDeferred<YoutubeChartsPage?>? = null

    override suspend fun topTracks(limit: Int): List<Track> = page()?.tracks?.take(limit).orEmpty()

    override suspend fun topArtists(limit: Int): List<ArtistRef> = page()?.artists?.take(limit).orEmpty()

    override suspend fun topAlbums(limit: Int): List<AlbumRef> {
        val client = extractor ?: return emptyList()
        albumsCached?.takeIf { nowMs() - it.atMs < ttlMs }?.let { return it.items.take(limit) }
        val seeds = page()?.artists.orEmpty().map { it.name }.filter { it.isNotBlank() }.take(4)
        val items = coroutineScope {
            seeds.map { query -> async(Dispatchers.IO) { runCatching { client.searchMusicAlbums(query, 12) }.getOrDefault(emptyList()) } }
                .awaitAll().flatten()
        }.mapNotNull { it.toMusicAlbumRefOrNull() }
            .distinctBy { it.source.identityKey }
            .take(MAX_ALBUMS)
        albumsCached = CachedItems(nowMs(), items)
        return items.take(limit)
    }

    override suspend fun editorialPlaylists(limit: Int): List<PlaylistRef> {
        val client = extractor ?: return emptyList()
        playlistsCached?.takeIf { nowMs() - it.atMs < ttlMs }?.let { return it.items.take(limit) }
        val items = coroutineScope {
            PLAYLIST_QUERIES.map { query -> async(Dispatchers.IO) { runCatching { client.searchMusicPlaylists(query, 24) }.getOrDefault(emptyList()) } }
                .awaitAll().flatten()
        }.mapNotNull { it.toPlaylistRefOrNull() }
            .distinctBy { it.source.identityKey }
            .take(MAX_PLAYLISTS)
        playlistsCached = CachedItems(nowMs(), items)
        return items.take(limit)
    }

    /** The country only travels with consent; otherwise the global chart, which identifies nobody. */
    private suspend fun consentedCountry(): String? =
        region.country().takeIf { settings.recsRegionalConsent.first() == true }

    /**
     * Memoized, request-collapsing fetch. The lock only ever guards the fields — never the network call
     * — so `topTracks` and `topArtists` overlap into a single download instead of queueing. Failures
     * aren't cached: the next Home load simply retries.
     */
    private suspend fun page(): YoutubeChartsPage? {
        val pending = mutex.withLock {
            cached?.takeIf { nowMs() - it.atMs < ttlMs }?.let { return it.page }
            inFlight?.let { return@withLock it to false }
            CompletableDeferred<YoutubeChartsPage?>().also { inFlight = it } to true
        }
        val (deferred, isLeader) = pending
        if (!isLeader) return deferred.await()

        val fetched = try {
            client.charts(consentedCountry())
        } catch (e: CancellationException) {
            mutex.withLock { inFlight = null }
            deferred.complete(null) // followers must not inherit this leader's cancellation
            throw e
        } catch (_: Exception) {
            null
        }
        mutex.withLock {
            if (fetched != null) cached = Cached(nowMs(), fetched)
            inFlight = null
        }
        deferred.complete(fetched)
        return fetched
    }

    private class Cached(val atMs: Long, val page: YoutubeChartsPage)
    private class CachedItems<T>(val atMs: Long, val items: List<T>)

    companion object {
        const val ID = "youtube-charts"
        private const val MAX_ALBUMS = 40
        private const val MAX_PLAYLISTS = 60
        private val PLAYLIST_QUERIES = listOf("YouTube Music Top Hits", "YouTube Music New Music", "YouTube Music Latin")
    }
}
