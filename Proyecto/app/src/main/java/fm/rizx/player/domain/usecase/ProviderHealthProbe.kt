package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.DashboardProvider
import fm.rizx.player.domain.provider.LyricsProvider
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderDescriptor
import fm.rizx.player.domain.provider.ProviderHealth
import fm.rizx.player.domain.provider.StreamingProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * Measures a provider's health with a cheap, timed probe (Phase 21) — a real per-kind call bounded by
 * [timeoutMs]. Fully isolated: any failure/timeout becomes [ProviderHealth.Down], never propagating.
 * Fake providers (no network) return instantly. [nowNanos] is injectable for deterministic tests.
 *
 * **Results are memoized for [ttlMs].** The probe is a real search per provider, and the Plugins screen
 * probes every registered one; without this, each visit to that screen fired ~25 network calls (and, for
 * plugin-backed providers, that many JS invocations through a single-threaded engine). A singleton, so
 * the cache outlives the screen's ViewModel — which is destroyed on every back-navigation.
 */
@javax.inject.Singleton
class ProviderHealthProbe @Inject constructor(
    private val nowNanos: () -> Long = { System.nanoTime() },
    private val timeoutMs: Long = 5_000,
    private val ttlMs: Long = 60_000,
) {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    suspend fun probe(descriptor: ProviderDescriptor): ProviderHealth {
        val start = nowNanos()
        cache[descriptor.id]?.takeIf { (start - it.atNanos) / 1_000_000 < ttlMs }?.let { return it.health }
        // Nothing cheap to call for this kind (playlists, discovery). Saying so beats the green "0 ms"
        // this used to report for every playlist provider without having tested anything — and it is
        // not cached, since it is a statement about the kind rather than a measurement.
        if (!isProbeable(descriptor)) return ProviderHealth.Unknown
        val health = try {
            withTimeout(timeoutMs) {
                when (descriptor) {
                    is MetadataProvider -> descriptor.search(SearchParams("music", limit = 1))
                    is StreamingProvider -> descriptor.searchForTrack(PROBE_TRACK)
                    is DashboardProvider -> descriptor.topTracks(1)
                    is LyricsProvider -> descriptor.getLyrics(PROBE_TRACK)
                    else -> Unit
                }
            }
            ProviderHealth.Ok((nowNanos() - start) / 1_000_000)
        } catch (e: TimeoutCancellationException) {
            ProviderHealth.Down("timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProviderHealth.Down(e.message ?: "error")
        }
        cache[descriptor.id] = Cached(health, nowNanos())
        return health
    }

    private fun isProbeable(descriptor: ProviderDescriptor): Boolean =
        descriptor is MetadataProvider || descriptor is StreamingProvider ||
            descriptor is DashboardProvider || descriptor is LyricsProvider

    private class Cached(val health: ProviderHealth, val atNanos: Long)

    private companion object {
        val PROBE_TRACK = Track(
            title = "Yesterday",
            artists = listOf(ArtistCredit("The Beatles")),
            source = ProviderRef("probe", "probe"),
        )
    }
}
