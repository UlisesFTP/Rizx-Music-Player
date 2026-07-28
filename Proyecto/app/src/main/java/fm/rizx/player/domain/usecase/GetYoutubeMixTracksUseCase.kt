package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.RadioMixSource
import javax.inject.Inject

/**
 * Fetches YouTube-Mix tracks to keep a search-originated radio going (`RadioMode.YOUTUBE`): what
 * YT Music's own autoplay would queue after [seed]. The seed and anything in [exclude]
 * (already-queued sources) are filtered out so the queue never repeats. An empty result is the
 * caller's signal to fall back to the artist radio — never a crash.
 */
class GetYoutubeMixTracksUseCase @Inject constructor(
    private val mix: RadioMixSource,
) {
    suspend operator fun invoke(seed: Track, exclude: Set<ProviderRef> = emptySet()): List<Track> {
        val blocked = exclude + seed.source
        return runCatching { mix.mixTracks(seed) }.getOrDefault(emptyList())
            .filter { it.source !in blocked }
            .distinctBy { it.source }
    }
}
