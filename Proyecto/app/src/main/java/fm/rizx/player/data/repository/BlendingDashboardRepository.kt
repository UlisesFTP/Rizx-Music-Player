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
 *
 * When the user has pinned the feed to a single platform, the inner fan-out already returns one
 * source per section, and the blend becomes a no-op that would only replace that platform's name with
 * "Rizx". So a single-source section passes straight through, keeping its real attribution — the
 * whole point of choosing a platform is seeing that it is that platform's chart.
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
        val tracks = if (feed.topTracks.size <= 1) feed.topTracks.flatMap { it.items } else blender.blendTracks(feed.topTracks)
        val visible = tracks.take(ENRICH_LIMIT)
        val enriched = runCatching { artwork.enrich(visible) }.getOrDefault(visible) + tracks.drop(ENRICH_LIMIT)
        HomeFeed(
            topTracks = withAttribution(feed.topTracks, enriched),
            topArtists = blended(feed.topArtists) { blender.blendArtists(it) },
            topAlbums = blended(feed.topAlbums) { blender.blendAlbums(it) },
            editorialPlaylists = blended(feed.editorialPlaylists) { blender.blendPlaylists(it) },
            newReleases = blended(feed.newReleases) { blender.blendAlbums(it) },
        )
    }

    /** One source needs no blending — skip the normalization work entirely and keep it as it came. */
    private fun <T> blended(
        original: List<AttributedResult<T>>,
        blend: (List<AttributedResult<T>>) -> List<T>,
    ): List<AttributedResult<T>> =
        if (original.size <= 1) original else withAttribution(original, blend(original))

    /** Keeps a single source's own attribution; synthesizes "Rizx" only for a real blend. */
    private fun <T> withAttribution(
        original: List<AttributedResult<T>>,
        items: List<T>,
    ): List<AttributedResult<T>> = when {
        items.isEmpty() -> emptyList()
        original.size <= 1 -> listOf(original.first().copy(items = items))
        else -> listOf(AttributedResult(ID, NAME, items))
    }

    companion object {
        const val ID = "blended"
        const val NAME = "Rizx"

        /** Only what the Home actually shows gets a cover lookup — the tail keeps its placeholder. */
        private const val ENRICH_LIMIT = 24
    }
}
