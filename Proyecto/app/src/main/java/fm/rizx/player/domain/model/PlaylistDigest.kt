package fm.rizx.player.domain.model

/**
 * What a playlist looks like from the outside, derived from the items it holds: up to four covers for a
 * 2×2 collage and the running time of everything in it.
 *
 * Kept apart from [PlaylistSummary] on purpose. The summary is one cheap SQL row per playlist and is
 * observed all over the app; this needs every stored item decoded, so it is its own flow that only the
 * Library collects.
 */
data class PlaylistDigest(
    /** The artwork of the first tracks that carry any, in playlist order, at most four. */
    val covers: List<ArtworkSet> = emptyList(),
    /** Sum of the known track durations; tracks without one count as zero. */
    val durationMs: Long = 0L,
)
