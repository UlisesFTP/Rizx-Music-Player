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
 */
class ProviderHealthProbe @Inject constructor(
    private val nowNanos: () -> Long = { System.nanoTime() },
    private val timeoutMs: Long = 5_000,
) {

    suspend fun probe(descriptor: ProviderDescriptor): ProviderHealth {
        val start = nowNanos()
        return try {
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
    }

    private companion object {
        val PROBE_TRACK = Track(
            title = "Yesterday",
            artists = listOf(ArtistCredit("The Beatles")),
            source = ProviderRef("probe", "probe"),
        )
    }
}
