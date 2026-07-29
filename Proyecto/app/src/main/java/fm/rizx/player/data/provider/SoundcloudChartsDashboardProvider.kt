package fm.rizx.player.data.provider

import fm.rizx.player.data.remote.soundcloud.SoundcloudExtractorClient
import fm.rizx.player.data.remote.soundcloud.SoundcloudIds
import fm.rizx.player.data.remote.soundcloud.toSoundcloudTrackOrNull
import fm.rizx.player.domain.model.DashboardCapability
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.DashboardProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Home-feed source over SoundCloud's "Top 50" chart kiosk (keyless, via NewPipe).
 *
 * Top tracks only: SoundCloud has no albums, and its other kiosk ("New & hot") also returns tracks,
 * so mapping it onto NEW_RELEASES — which the feed renders as albums — would misrepresent it.
 *
 * Tracks carry SoundCloud-native refs, so playing one from the feed resolves against SoundCloud
 * directly instead of being matched by title elsewhere. The kiosk is memoized for [ttlMs].
 */
class SoundcloudChartsDashboardProvider(
    private val client: SoundcloudExtractorClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = 30 * 60_000L,
) : DashboardProvider {

    override val id: String = SoundcloudIds.DASHBOARD
    override val kind: ProviderKind = ProviderKind.DASHBOARD
    override val name: String = "SoundCloud"
    override val dashboardCapabilities: Set<DashboardCapability> =
        setOf(DashboardCapability.TOP_TRACKS)

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CachedKiosk>()

    override suspend fun topTracks(limit: Int): List<Track> = kiosk(SoundcloudIds.KIOSK_TOP_50).take(limit)

    private suspend fun kiosk(kind: String): List<Track> = mutex.withLock {
        cache[kind]?.takeIf { nowMs() - it.atMs < ttlMs }?.tracks
            ?: withContext(io) {
                client.charts(kind, CHART_LIMIT).mapNotNull { it.toSoundcloudTrackOrNull() }
            }.also { cache[kind] = CachedKiosk(nowMs(), it) }
    }

    private class CachedKiosk(val atMs: Long, val tracks: List<Track>)

    private companion object {
        const val CHART_LIMIT = 30
    }
}
