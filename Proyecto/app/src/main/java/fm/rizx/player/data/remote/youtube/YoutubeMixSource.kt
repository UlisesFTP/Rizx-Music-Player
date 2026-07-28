package fm.rizx.player.data.remote.youtube

import fm.rizx.player.data.artwork.TrackArtworkEnricher
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.RadioMixSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [RadioMixSource] over the YouTube extractor: resolves the seed to a videoId — direct for
 * YouTube-native tracks, one Music search otherwise (after the first refill the queue tail is
 * YouTube-native, so the search cost is paid at most once per radio) — then pulls the Mix seeded by
 * it. All failures degrade to an empty list; the service then falls back to the artist radio.
 *
 * Mix rows arrive with a **video thumbnail**, not cover art, so every track is run through
 * [TrackArtworkEnricher] to borrow the real (square, full-size) cover from Deezer — otherwise the
 * queue after a search play would be a row of letterboxed video stills. That's cosmetic and
 * best-effort: a failed lookup keeps the thumbnail, and a failed enrich pass keeps the whole list.
 */
class YoutubeMixSource(
    private val client: YoutubeExtractorClient,
    private val artwork: TrackArtworkEnricher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RadioMixSource {

    override suspend fun mixTracks(seed: Track): List<Track> = mixTracks(seed, MIX_LIMIT)

    override suspend fun mixTracks(seed: Track, limit: Int): List<Track> = withContext(io) {
        val tracks = runCatching {
            val videoId = seedVideoId(seed) ?: return@runCatching emptyList()
            // One mix page is fetched either way; [limit] only bounds how many get an artwork lookup,
            // which is the expensive part (one provider search each).
            client.mix(videoId, MIX_LIMIT)
                .mapNotNull { it.toTrackOrNull() }
                .filter { it.source.id != videoId } // the mix returns the seed video itself first
                .take(limit)
        }.getOrDefault(emptyList())

        if (tracks.isEmpty()) {
            tracks
        } else {
            runCatching { artwork.enrich(tracks, upgradeFrom = setOf(YoutubeIds.STREAMING)) }
                .getOrDefault(tracks)
        }
    }

    private fun seedVideoId(seed: Track): String? {
        if (seed.source.provider == YoutubeIds.STREAMING && isYoutubeVideoId(seed.source.id)) return seed.source.id
        val artist = seed.artists.firstOrNull()?.name.orEmpty()
        val query = listOf(artist, seed.title).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null
        return client.searchSongs(query, 1).firstOrNull()?.url?.let { youtubeVideoId(it) }
    }

    private companion object {
        /** One mix page is ~25 items; the refill re-seeds long before they run out. */
        const val MIX_LIMIT = 25
    }
}
