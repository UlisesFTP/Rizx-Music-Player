package fm.rizx.player.data.provider

import fm.rizx.player.core.region.RegionResolver
import fm.rizx.player.data.remote.applemusic.AppleMusicRssApi
import fm.rizx.player.data.remote.applemusic.toAlbumRefOrNull
import fm.rizx.player.data.remote.applemusic.toPlaylistRefOrNull
import fm.rizx.player.data.remote.applemusic.toTrackOrNull
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.DashboardCapability
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.DashboardProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Dashboard source over Apple Music's keyless most-played RSS — the "most played in <country>" row
 * of the Home feed.
 *
 * The user's **own** country is only ever sent with the in-app regional consent. Without it the feed
 * falls back to a fixed [DEFAULT_STOREFRONT] (Apple has no global storefront), which reveals nothing
 * about them — the same shape as the Spotify source's global chart. That fallback exists because the
 * feed can now be set to Apple Music alone, and "you chose this source and got an empty screen" is
 * not an acceptable answer to a privacy default. The chart is memoized per storefront for [ttlMs];
 * failures propagate to the repository, which isolates them per section.
 */
class AppleMusicDashboardProvider(
    private val api: AppleMusicRssApi,
    private val region: RegionResolver,
    private val settings: SettingsRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = 30 * 60_000L,
    /** Discovers the "Top 100" country charts. Null in tests, which then see only the RSS rows. */
    private val browse: fm.rizx.player.data.remote.applemusic.AppleMusicBrowsePage? = null,
) : DashboardProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.DASHBOARD
    override val name: String = "Apple Music"
    override val dashboardCapabilities: Set<DashboardCapability> = setOf(
        DashboardCapability.TOP_TRACKS,
        DashboardCapability.TOP_ALBUMS,
        DashboardCapability.EDITORIAL_PLAYLISTS,
    )

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CachedFeed>()

    override suspend fun topTracks(limit: Int): List<Track> =
        feed(SONGS) { country -> api.mostPlayedSongs(country, RSS_LIMIT).feed.results.mapNotNull { it.toTrackOrNull() } }
            .take(limit)

    override suspend fun topAlbums(limit: Int): List<AlbumRef> =
        feed(ALBUMS) { country -> api.topAlbums(country, RSS_LIMIT).feed.results.mapNotNull { it.toAlbumRefOrNull() } }
            .take(limit)

    /**
     * Apple's playlists, charts first: the **"Top 100"** family (Global plus one per country,
     * discovered from the browse page) ahead of the curated editorial rows ("Today's Hits",
     * "Rap Life", the Essentials).
     *
     * Charts lead because they are what someone opening a music app is looking for, and because they
     * are the same everywhere — a user in any storefront can meaningfully browse "Top 100: Japan".
     * Every row carries a public `music.apple.com` URL on its ref, which is what
     * `AppleMusicPlaylistProvider` reopens; a row without one is dropped rather than shown as a card
     * that would open empty.
     */
    override suspend fun editorialPlaylists(limit: Int): List<PlaylistRef> {
        val storefront = storefront()
        val charts = browse?.let { page -> withContext(io) { runCatching { page.topCharts(storefront) }.getOrDefault(emptyList()) } }
            .orEmpty()
        val curated = feed(PLAYLISTS) { country ->
            api.topPlaylists(country, RSS_LIMIT).feed.results.mapNotNull { it.toPlaylistRefOrNull() }
        }
        return (charts + curated)
            .filter { !it.source.url.isNullOrBlank() }
            .distinctBy { it.source.id }
            .take(limit)
    }

    /** The user's own storefront only with consent; a fixed default otherwise (reveals nothing). */
    private suspend fun storefront(): String {
        val consented = settings.recsRegionalConsent.first() == true
        return (if (consented) region.country() else null) ?: DEFAULT_STOREFRONT
    }

    /** One memo per feed kind per storefront: the sections are fetched independently by the fan-out. */
    private suspend fun <T : Any> feed(kind: String, fetch: suspend (String) -> List<T>): List<T> {
        val country = storefront()
        val key = "$kind/$country"
        return mutex.withLock {
            val memo = cache[key]?.takeIf { nowMs() - it.atMs < ttlMs }
            if (memo != null) {
                @Suppress("UNCHECKED_CAST")
                memo.items as List<T>
            } else {
                withContext(io) { fetch(country) }.also { cache[key] = CachedFeed(nowMs(), it) }
            }
        }
    }

    private class CachedFeed(val atMs: Long, val items: List<Any>)

    companion object {
        const val ID = "applemusic-charts"

        /** Used when the user hasn't consented to sharing their region — a fixed, non-personal choice. */
        const val DEFAULT_STOREFRONT = "us"

        private const val RSS_LIMIT = 25
        private const val SONGS = "songs"
        private const val ALBUMS = "albums"
        private const val PLAYLISTS = "playlists"
    }
}
