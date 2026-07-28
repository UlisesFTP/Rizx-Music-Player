package fm.rizx.player.data.repository

import fm.rizx.player.data.artwork.TrackArtworkEnricher
import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.repository.DashboardRepository
import fm.rizx.player.domain.usecase.RecsBlender
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decorator over the fan-out [DashboardRepositoryImpl]: applies the [RecsBlender] (dedup keeping
 * Deezer + weighted interleave) and hands each section back as **one** synthesized
 * [AttributedResult], so `HomeScreen`'s existing `flatMap { it.items }` renders the blend with zero
 * UI churn. Also borrows Deezer covers for the first blended tracks that came without artwork
 * (Spotify's embed rows carry none) — enrichment is cosmetic and never fails the feed.
 */
class BlendingDashboardRepository(
    private val inner: DashboardRepository,
    private val blender: RecsBlender,
    private val artwork: TrackArtworkEnricher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DashboardRepository {

    /** On [io]: the blender normalizes (regex + Unicode NFD) every item of every section. */
    override suspend fun homeFeed(): HomeFeed = withContext(io) {
        val feed = inner.homeFeed()
        val tracks = blender.blendTracks(feed.topTracks)
        val visible = tracks.take(ENRICH_LIMIT)
        val enriched = runCatching { artwork.enrich(visible) }.getOrDefault(visible) + tracks.drop(ENRICH_LIMIT)
        HomeFeed(
            topTracks = attributed(enriched),
            topArtists = attributed(blender.blendArtists(feed.topArtists)),
            topAlbums = attributed(blender.blendAlbums(feed.topAlbums)),
            editorialPlaylists = attributed(blender.blendPlaylists(feed.editorialPlaylists)),
            newReleases = attributed(blender.blendAlbums(feed.newReleases)),
        )
    }

    private fun <T> attributed(items: List<T>): List<AttributedResult<T>> =
        if (items.isEmpty()) emptyList() else listOf(AttributedResult(ID, NAME, items))

    companion object {
        const val ID = "blended"
        const val NAME = "Rizx"

        /** Only what the Home actually shows gets a cover lookup — the tail keeps its placeholder. */
        private const val ENRICH_LIMIT = 24
    }
}
