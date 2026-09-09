package fm.rizx.player.ui.components

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import fm.rizx.player.domain.playback.PlaybackState
import fm.rizx.player.domain.playback.smoothPositionMs

/**
 * Produces a frame-paced playback fraction from MediaController's inexpensive position samples.
 *
 * The state is intended to be read from a Canvas draw block. That keeps the 60 Hz invalidation local
 * to the progress indicator instead of recomposing the surrounding player UI on every frame.
 */
@Composable
fun rememberSmoothPlaybackProgress(
    sampledProgress: Float,
    positionMs: Long,
    durationMs: Long,
    sampledAtElapsedMs: Long,
    isAdvancing: Boolean,
    speed: Float,
): State<Float> {
    val snapshot = remember(positionMs, durationMs, sampledAtElapsedMs, isAdvancing, speed) {
        PlaybackState(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isAdvancing,
            speed = speed,
            sampledAtElapsedMs = sampledAtElapsedMs,
        )
    }
    val exactProgress = if (durationMs > 0L && sampledAtElapsedMs > 0L) {
        snapshot.progress
    } else {
        sampledProgress.coerceIn(0f, 1f)
    }

    return produceState(
        exactProgress,
        sampledProgress,
        snapshot,
    ) {
        value = exactProgress
        if (!isAdvancing || durationMs <= 0L || sampledAtElapsedMs <= 0L) return@produceState

        while (true) {
            withFrameNanos { }
            val visualPositionMs = snapshot.smoothPositionMs(SystemClock.elapsedRealtime())
            value = (visualPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }
    }
}
