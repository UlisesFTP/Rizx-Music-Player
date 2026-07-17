package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.Track

/**
 * Routes lyrics lookups to the active lyrics provider (Phase 15). Returns `null` when no lyrics
 * provider is active or the provider has no lyrics for the track; transport failures propagate.
 */
interface LyricsRepository {
    suspend fun lyricsFor(track: Track): String?
}
