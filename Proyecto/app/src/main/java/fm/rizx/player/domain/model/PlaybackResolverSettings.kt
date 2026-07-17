package fm.rizx.player.domain.model

/**
 * Tunables for [fm.rizx.player.domain.usecase.StreamingResolver] (NUCLEAR_UPSTREAM_STUDY.md §5.4).
 *
 * @param streamExpiryMs how long a resolved [Stream] URL stays valid before it must be re-resolved.
 *   Default **3 h** — chosen explicitly over the plugin docs' ~1 h (the study flags this as a
 *   "pick one" decision); resolved URLs (e.g. YouTube) expire after hours.
 * @param streamResolutionRetries the **maximum number of resolution attempts** (not extra retries)
 *   for a single candidate before it is marked failed. Named after upstream
 *   `playback.streamResolutionRetries`; default **2**.
 * @param retryBaseDelayMs base backoff between attempts, doubling each time (500 ms → 1 s → 2 s …).
 */
data class PlaybackResolverSettings(
    val streamExpiryMs: Long = 3 * 60 * 60 * 1000L,
    val streamResolutionRetries: Int = 2,
    val retryBaseDelayMs: Long = 500L,
)
