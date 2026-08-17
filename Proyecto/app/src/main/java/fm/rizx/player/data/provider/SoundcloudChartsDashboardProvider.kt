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
 * Home-feed source over SoundCloud's public "New & hot" chart kiosk (keyless, via NewPipe).
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
    override val name: String = "SoundCloud · New & hot"
    override val dashboardCapabilities: Set<DashboardCapability> =
        setOf(DashboardCapability.TOP_TRACKS)

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CachedKiosk>()

    // "New & hot", not "Top 50": SoundCloud retired the latter's endpoint. NewPipe maps that kiosk to
    // `charts?kind=top`, which returns **404** live (verified with a valid client_id, while
    // `kind=trending` returns 200) — so this row, and with it the whole "SoundCloud only" feed option,
    // had been silently empty. Nothing failed loudly, which is why it went unnoticed.
    override suspend fun topTracks(limit: Int): List<Track> = kiosk(SoundcloudIds.KIOSK_NEW_HOT).take(limit)

    private suspend fun kiosk(kind: String): List<Track> = mutex.withLock {
        cache[kind]?.takeIf { nowMs() - it.atMs < ttlMs }?.tracks
            ?: withContext(io) {
                client.charts(kind, CHART_LIMIT).mapNotNull { it.toSoundcloudTrackOrNull() }
            }.also { cache[kind] = CachedKiosk(nowMs(), it) }
    }

    private class CachedKiosk(val atMs: Long, val tracks: List<Track>)

    private companion object {
        const val CHART_LIMIT = 50
    }
}
