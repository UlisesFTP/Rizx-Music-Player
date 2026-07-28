package fm.rizx.player.ui.lyrics

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import fm.rizx.player.domain.playback.PlaybackState
import fm.rizx.player.domain.playback.smoothPositionMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** How often the loop re-checks whether playback came back, while paused with lyrics open. */
private const val IDLE_POLL_MS = 200L

/**
 * The one clock the karaoke view reads.
 *
 * [frameMs] is a `mutableLongState` on purpose, and it is meant to be read **inside a draw lambda** —
 * `sweep = { timeline.stateAt(clock.frameMs, offsetMs) }`, invoked from `drawWithContent`. Read there, a
 * change invalidates the draw phase of that one node: nothing recomposes, nothing re-measures, and the
 * `LazyColumn` never learns the clock exists. Read it in a composable body instead and the whole lyric
 * list recomposes sixty times a second, which is the mistake this class exists to make hard.
 */
@Stable
class LyricsClock internal constructor() {

    var frameMs by mutableLongStateOf(0L)
        private set

    private var lastShown = 0L
    private var lastItemId: String? = null

    /**
     * Moves the clock to what [state] says the position is at [nowElapsedMs].
     *
     * The guard in the middle is the whole point. Extrapolating between 250 ms samples means the guess
     * occasionally overshoots, and when the next real sample lands slightly behind it the sweep would
     * visibly jerk backwards four times a second. Small regressions are therefore ignored, while a
     * genuine backwards seek — anything beyond [SEEK_JUMP_MS] — snaps immediately, because refusing to
     * rewind after the user rewinds is a far worse bug than a few milliseconds of drift.
     */
    internal fun advance(state: PlaybackState, nowElapsedMs: Long) {
        val raw = state.smoothPositionMs(nowElapsedMs)
        val next = when {
            // A different song has no history worth preserving.
            state.currentQueueItemId != lastItemId -> raw
            // Paused, or seeking while paused: the sample is the truth, in both directions.
            !state.isPlaying -> raw
            raw >= lastShown -> raw
            lastShown - raw > SEEK_JUMP_MS -> raw
            else -> lastShown
        }
        lastItemId = state.currentQueueItemId
        lastShown = next
        frameMs = next
    }

    private companion object {
        /** Below this, a backwards step is sampling jitter. Above it, someone pressed rewind. */
        const val SEEK_JUMP_MS = 500L
    }
}

/**
 * A [LyricsClock] fed by [state], ticking at [hz] while [enabled].
 *
 * [state] is taken as a `StateFlow` rather than a value **so that reading playback never recomposes the
 * caller**. A `PlaybackState` parameter would drag the whole lyric list through recomposition four times
 * a second before the frame loop had done any work at all.
 *
 * The loop is gated four ways — a timed lyric is on screen ([enabled]), the song is actually playing, the
 * lifecycle is `RESUMED`, and only the lyrics screen ever calls this — so it cannot outlive the one view
 * that needs it. See `docs/adr/0016-karaoke-frame-loop.md`.
 */
@Composable
fun rememberLyricsClock(state: StateFlow<PlaybackState>, enabled: Boolean, hz: Int): LyricsClock {
    val clock = remember { LyricsClock() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // The always-on path: every engine sample lands, loop or no loop. Cheap (4 Hz) and it is what keeps
    // a paused song, a plain lyric and a just-opened screen correct without any frame machinery.
    LaunchedEffect(state) {
        state.collect { clock.advance(it, SystemClock.elapsedRealtime()) }
    }

    LaunchedEffect(state, enabled, hz) {
        if (!enabled) return@LaunchedEffect
        // Below 60 Hz we sleep between frames rather than requesting one and throwing it away: a frame
        // never asked for costs nothing, a frame asked for and discarded costs a full draw.
        val minFrameMs = if (hz >= 60) 0L else (1_000L / hz.coerceAtLeast(1))
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                withFrameMillis { }
                val now = SystemClock.elapsedRealtime()
                val snapshot = state.value
                clock.advance(snapshot, now)
                // Paused with the screen open: stop asking for frames until playback comes back.
                if (!snapshot.isPlaying) delay(IDLE_POLL_MS) else if (minFrameMs > 0L) delay(minFrameMs)
            }
        }
    }
    return clock
}
