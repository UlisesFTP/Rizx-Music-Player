package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.DashboardRepository
import fm.rizx.player.domain.repository.MetadataRepository
import javax.inject.Inject

/**
 * Fetches ~similar "radio" tracks to keep a feed/search radio queue going. Tries the active metadata
 * provider's [radioTracks][MetadataRepository.radioTracks] (Deezer artist-radio by default); if that
 * comes back empty, falls back to the global chart ([DashboardRepository]) shuffled. The [seed] and
 * anything in [exclude] (already-queued sources) are filtered out so the queue never repeats. All
 * network is `runCatching`-guarded — a failed fetch just yields fewer/zero tracks, never a crash.
 */
class GetRadioTracksUseCase @Inject constructor(
    private val metadata: MetadataRepository,
    private val dashboard: DashboardRepository,
) {
    suspend operator fun invoke(seed: Track, exclude: Set<ProviderRef> = emptySet()): List<Track> {
        val blocked = exclude + seed.source
        val radio = runCatching { metadata.radioTracks(seed) }.getOrDefault(emptyList())
        val pool = radio.ifEmpty {
            runCatching { dashboard.homeFeed().topTracks.flatMap { it.items } }.getOrDefault(emptyList()).shuffled()
        }
        return pool.filter { it.source !in blocked }.distinctBy { it.source }
    }
}
