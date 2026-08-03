package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.Track

/**
 * Builds the [HomeFeed] by **fanning out** over all registered dashboard providers (Phase 19). Each
 * provider/section is isolated: a slow or failing provider degrades gracefully (its section absent),
 * never crashing the feed.
 */
interface DashboardRepository {
    suspend fun homeFeed(): HomeFeed

    /**
     * Resolves a mood station to what it is playing right now, asking the provider that supplied it
     * ([providerId] travels with the station via its `AttributedResult`). Unknown provider or a failed
     * fetch degrade to empty — a dead chip, never a crash. Defaulted so decorators and fakes that only
     * care about the feed need not implement it.
     */
    suspend fun stationTracks(providerId: String, stationId: String, limit: Int): List<Track> = emptyList()
}
