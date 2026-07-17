package fm.rizx.player.domain.playback

/**
 * Coarse playback status. Android's `Player.STATE_*` is richer than hifi's 3-value status
 * (NUCLEAR_UPSTREAM_STUDY.md §6.1), so this is the source of truth mapped from ExoPlayer.
 */
enum class PlaybackStatus { IDLE, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

/**
 * Immutable snapshot of the engine's playback truth, observed by the UI. All times are **milliseconds**
 * (§6.7 — `Track`/`Stream` durations and Media3 are ms; do not confuse with upstream's seconds).
 * [currentQueueItemId] ties the state to a [fm.rizx.player.domain.model.QueueItem].
 */
data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val currentQueueItemId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val error: String? = null,
) {
    /** Playback position as a 0..1 fraction, or 0 when the duration is unknown. */
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /**
     * True while the engine is buffering a track the user *intends to play* (tapped play / a new song),
     * so the UI can show a loading spinner and block the play button until it's ready. A track buffering
     * while paused (e.g. a restored session) is **not** loading — the user can still hit play.
     */
    val isLoading: Boolean
        get() = status == PlaybackStatus.BUFFERING && playWhenReady
}
