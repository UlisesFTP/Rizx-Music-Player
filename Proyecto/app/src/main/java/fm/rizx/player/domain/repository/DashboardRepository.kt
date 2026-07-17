package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.HomeFeed

/**
 * Builds the [HomeFeed] by **fanning out** over all registered dashboard providers (Phase 19). Each
 * provider/section is isolated: a slow or failing provider degrades gracefully (its section absent),
 * never crashing the feed.
 */
interface DashboardRepository {
    suspend fun homeFeed(): HomeFeed
}
