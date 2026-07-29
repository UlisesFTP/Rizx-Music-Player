package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.RadioMixSource
import javax.inject.Inject

/**
 * Fetches mix tracks to keep a song-seeded radio going: what the chosen engine would queue after
 * [seed]. The seed and anything in [exclude] (already-queued sources) are filtered out so the queue
 * never repeats. An empty result is the caller's signal to fall back to the artist radio — never a
 * crash.
 *
 * The injected [mix] is the YouTube Mix (the default engine); [invoke] takes an optional `source`
 * override so the queue refill can run the same filtering for Apple Music or SoundCloud without a
 * second use case that would only differ in which object it called.
 */
class GetYoutubeMixTracksUseCase @Inject constructor(
    private val mix: RadioMixSource,
) {
    suspend operator fun invoke(
        seed: Track,
        exclude: Set<ProviderRef> = emptySet(),
        source: RadioMixSource = mix,
    ): List<Track> {
        val blocked = exclude + seed.source
        return runCatching { source.mixTracks(seed) }.getOrDefault(emptyList())
            .filter { it.source !in blocked }
            .distinctBy { it.source }
    }
}
