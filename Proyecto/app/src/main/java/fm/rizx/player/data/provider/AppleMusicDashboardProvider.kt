package fm.rizx.player.data.provider

import fm.rizx.player.core.region.RegionResolver
import fm.rizx.player.data.remote.applemusic.AppleMusicRssApi
import fm.rizx.player.data.remote.applemusic.toTrackOrNull
import fm.rizx.player.domain.model.DashboardCapability
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
 * of the Home feed. Strictly consent-gated: without the in-app regional consent, or with no
 * resolvable country, it contributes **nothing** (Apple has no global storefront to fall back to,
 * and nothing regional may be fetched without consent). The chart is memoized per country for
 * [ttlMs]; failures propagate to the repository, which isolates them per section.
 */
class AppleMusicDashboardProvider(
    private val api: AppleMusicRssApi,
    private val region: RegionResolver,
    private val settings: SettingsRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = 30 * 60_000L,
) : DashboardProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.DASHBOARD
    override val name: String = "Apple Music"
    override val dashboardCapabilities: Set<DashboardCapability> = setOf(DashboardCapability.TOP_TRACKS)

    private val mutex = Mutex()
    private var cache: CachedChart? = null

    override suspend fun topTracks(limit: Int): List<Track> {
        if (settings.recsRegionalConsent.first() != true) return emptyList()
        val country = region.country() ?: return emptyList()
        return chart(country).take(limit)
    }

    private suspend fun chart(country: String): List<Track> = mutex.withLock {
        cache?.takeIf { it.country == country && nowMs() - it.atMs < ttlMs }?.tracks
            ?: withContext(io) {
                api.mostPlayedSongs(country).feed.results.mapNotNull { it.toTrackOrNull() }
            }.also { cache = CachedChart(country, nowMs(), it) }
    }

    private class CachedChart(val country: String, val atMs: Long, val tracks: List<Track>)

    companion object {
        const val ID = "applemusic-charts"
    }
}
