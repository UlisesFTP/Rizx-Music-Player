package fm.rizx.player.playback

import androidx.media3.common.Player
import fm.rizx.player.domain.playback.PlaybackStatus

/**
 * Maps ExoPlayer's `Player.STATE_*` + `playWhenReady` + an error flag to the domain [PlaybackStatus].
 * Pure over the `Player.STATE_*` int constants, so it is unit-testable without a running player.
 * `STATE_READY` splits on `playWhenReady`: playing vs paused-but-ready (§6.1 — Android's states are
 * richer than hifi's three).
 */
fun playbackStatusOf(playbackState: Int, playWhenReady: Boolean, hasError: Boolean): PlaybackStatus =
    when {
        hasError -> PlaybackStatus.ERROR
        playbackState == Player.STATE_IDLE -> PlaybackStatus.IDLE
        playbackState == Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
        playbackState == Player.STATE_ENDED -> PlaybackStatus.ENDED
        playbackState == Player.STATE_READY && playWhenReady -> PlaybackStatus.PLAYING
        playbackState == Player.STATE_READY -> PlaybackStatus.PAUSED
        else -> PlaybackStatus.IDLE
    }
