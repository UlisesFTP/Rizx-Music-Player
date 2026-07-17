package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.flow.Flow

/**
 * History of tracks the user has played (Phase 15). Dedup is by [Track] identity (`source`), so a
 * track appears once, at its most-recent play time; the list is newest-first and bounded.
 */
interface RecentlyPlayedRepository {
    /** Most-recently-played tracks, newest first, up to [limit]. */
    fun recent(limit: Int = 30): Flow<List<Track>>

    /** Records [track] as played now (resolution state is stripped before storing). */
    suspend fun record(track: Track)

    /** Clears the entire history. */
    suspend fun clear()
}
