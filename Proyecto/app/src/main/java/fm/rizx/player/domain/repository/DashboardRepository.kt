package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.GenreFeed
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.MoodStation
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Builds the [HomeFeed] by **fanning out** over all registered dashboard providers (Phase 19). Each
 * provider/section is isolated: a slow or failing provider degrades gracefully (its section absent),
 * never crashing the feed.
 */
interface DashboardRepository {
    suspend fun homeFeed(): HomeFeed

    /**
     * The ids of the sources that would actually contribute right now — registered, of dashboard kind,
     * and not switched off — re-emitting whenever that set changes.
     *
     * Home folds this into its cache key and refetches when it changes. Without it, turning a source
     * on or off in Plugins left the cached feed in place for up to the cache's half-hour life: the
     * setting appeared to do nothing. Defaulted to empty so decorators and fakes need not implement it.
     */
    fun activeSourceIds(): Flow<List<String>> = flowOf(emptyList())

    /**
     * Resolves a mood station to what it is playing right now, asking the provider that supplied it
     * ([providerId] travels with the station via its `AttributedResult`). Unknown provider or a failed
     * fetch degrade to empty — a dead chip, never a crash. Defaulted so decorators and fakes that only
     * care about the feed need not implement it.
     */
    suspend fun stationTracks(providerId: String, stationId: String, limit: Int): List<Track> = emptyList()

    /**
     * One genre's songs, playlists, artists and albums, from the first enabled provider that knows the
     * id. Unlike [homeFeed] this does **not** blend: genre ids belong to a single catalogue, so merging
     * two providers' answers would mean merging two unrelated groupings.
     */
    suspend fun genreFeed(genreId: String, limit: Int): GenreFeed = GenreFeed()

    /**
     * Every mood/genre station the enabled providers offer, attributed — the "See all" behind Home's
     * grid, which shows a preview. Separate from [homeFeed] so opening that screen costs one section's
     * fetch rather than a whole feed's fan-out and blend.
     */
    suspend fun moodStations(limit: Int): List<AttributedResult<MoodStation>> = emptyList()
}
