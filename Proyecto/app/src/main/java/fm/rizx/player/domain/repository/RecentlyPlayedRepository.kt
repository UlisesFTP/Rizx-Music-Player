package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.PlayOutcome
import fm.rizx.player.domain.model.PlayStat
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * History of tracks the user has played (Phase 15). Dedup is by [Track] identity (`source`), so a
 * track appears once, at its most-recent play time; the list is newest-first and bounded.
 *
 * Since the recommendation engine's v2 it is also the **listening log**: [stats] carries the counters
 * behind each row (plays, completions, skips, time of day), which is what lets the Home tell a song
 * played thirty times from one opened once and abandoned.
 */
interface RecentlyPlayedRepository {
    /** Most-recently-played tracks, newest first, up to [limit]. */
    fun recent(limit: Int = 30): Flow<List<Track>>

    /**
     * The same history with its counters, newest first.
     *
     * Defaulted so a fake that only knows how to answer [recent] keeps working: the counters come back
     * neutral, which the weighting is written to treat as "no information" rather than "bad".
     */
    fun stats(limit: Int = 200): Flow<List<PlayStat>> =
        recent(limit).map { tracks -> tracks.map { PlayStat(track = it) } }

    /** Records that [track] started playing now (resolution state is stripped before storing). */
    suspend fun record(track: Track)

    /**
     * Records how a play ended. [listenedMs] is how far the listener actually got.
     *
     * Defaulted to a no-op for the same reason as [stats] — and because a caller that cannot tell how a
     * play ended should simply not say.
     */
    suspend fun recordOutcome(source: ProviderRef, listenedMs: Long, outcome: PlayOutcome) = Unit

    /** Clears the entire history. */
    suspend fun clear()
}
