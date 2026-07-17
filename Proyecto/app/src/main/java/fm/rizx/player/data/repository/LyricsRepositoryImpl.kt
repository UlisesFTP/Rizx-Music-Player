package fm.rizx.player.data.repository

import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.LyricsProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.repository.LyricsRepository

/**
 * Dispatches to the active [LyricsProvider] in the registry. Lyrics are optional: if none is active
 * (or the active descriptor isn't a lyrics provider), returns `null` rather than throwing.
 */
class LyricsRepositoryImpl(
    private val registry: ProviderRegistry,
) : LyricsRepository {

    override suspend fun lyricsFor(track: Track): String? {
        val provider = registry.activeDescriptor(ProviderKind.LYRICS) as? LyricsProvider ?: return null
        return provider.getLyrics(track)
    }
}
