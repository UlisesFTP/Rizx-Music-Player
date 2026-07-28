package fm.rizx.player.domain.provider

import fm.rizx.player.domain.model.Track

/**
 * Source of YouTube-Mix "radio" tracks (`RadioMode.YOUTUBE`): given a seed track, returns what
 * YT Music's own autoplay would queue after it. Implemented in the data layer over the YouTube
 * extractor; the domain only ever sees [Track]s. Implementations must swallow their own failures
 * and return empty — a broken source degrades, it never crashes the refill.
 */
interface RadioMixSource {
    /** YT-Mix recommendations for [seed], in YouTube's order, excluding the seed itself. */
    suspend fun mixTracks(seed: Track): List<Track>

    /**
     * At most [limit] recommendations. A Home carousel shows ten of them, and filling in cover art
     * costs one provider search per track — so asking for what will actually be drawn, rather than a
     * whole queue's worth, is the difference between ten lookups and twenty-five. The playback refill
     * keeps calling the unbounded version.
     *
     * Defaulted so implementations (and test fakes) need only supply [mixTracks].
     */
    suspend fun mixTracks(seed: Track, limit: Int): List<Track> = mixTracks(seed).take(limit)
}
