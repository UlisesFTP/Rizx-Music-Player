package fm.rizx.player.data.provider

import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.RadioMixSource

/**
 * Adapts a [MetadataProvider]'s own `radioTracks` into the [RadioMixSource] the queue refill speaks,
 * so an "up next" engine is just a provider that knows how to continue from a song.
 *
 * Apple Music (artist catalogue + same-genre) and SoundCloud (its own related-tracks endpoint) reach
 * the selector through this, exactly as the YouTube Mix does through its own source — no separate
 * engine hierarchy, and a failing provider yields an empty list so the caller's Deezer fallback runs.
 */
class MetadataRadioMixSource(
    private val provider: MetadataProvider,
) : RadioMixSource {

    override suspend fun mixTracks(seed: Track): List<Track> =
        runCatching { provider.radioTracks(seed) }.getOrDefault(emptyList())

    override suspend fun mixTracks(seed: Track, limit: Int): List<Track> = mixTracks(seed).take(limit)
}
