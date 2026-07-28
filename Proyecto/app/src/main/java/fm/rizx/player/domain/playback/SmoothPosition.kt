package fm.rizx.player.domain.playback

/**
 * Turns the engine's 4 Hz position samples into a position that can be read at any instant.
 *
 * The player is polled every 250 ms, which is plenty for a scrubber and useless for karaoke: a quarter of
 * a second is most of a short word. Rather than poll harder — which costs a binder call each time and
 * still quantises — the sample is extrapolated forward using the device's monotonic uptime clock, exactly
 * the way a media clock is meant to work.
 */

/** How far ahead of a sample we are willing to guess. Past this the loop stalled and guessing is lying. */
private const val MAX_EXTRAPOLATION_MS = 1_000L

/**
 * The playback position at [nowElapsedMs] (a `SystemClock.elapsedRealtime` reading).
 *
 * Paused playback returns the sample untouched: there is nothing to extrapolate, and a frozen sweep is
 * the correct picture of a frozen song.
 */
fun PlaybackState.smoothPositionMs(nowElapsedMs: Long): Long {
    if (!isPlaying || sampledAtElapsedMs <= 0L) return positionMs
    val elapsed = (nowElapsedMs - sampledAtElapsedMs).coerceIn(0L, MAX_EXTRAPOLATION_MS)
    val projected = positionMs + (elapsed * speed).toLong()
    return if (durationMs > 0L) projected.coerceAtMost(durationMs) else projected
}
