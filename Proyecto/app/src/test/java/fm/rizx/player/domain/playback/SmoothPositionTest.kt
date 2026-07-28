package fm.rizx.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SmoothPositionTest {

    private fun state(
        positionMs: Long = 10_000,
        sampledAt: Long = 1_000_000,
        playing: Boolean = true,
        speed: Float = 1f,
        durationMs: Long = 0,
    ) = PlaybackState(
        positionMs = positionMs,
        sampledAtElapsedMs = sampledAt,
        isPlaying = playing,
        speed = speed,
        durationMs = durationMs,
    )

    @Test
    fun `the position moves with real time between samples`() {
        val s = state()

        assertEquals(10_000L, s.smoothPositionMs(1_000_000))
        assertEquals(10_100L, s.smoothPositionMs(1_000_100))
        assertEquals(10_250L, s.smoothPositionMs(1_000_250))
    }

    @Test
    fun `a paused song is frozen at its sample`() {
        val s = state(playing = false)

        assertEquals(10_000L, s.smoothPositionMs(1_000_000))
        assertEquals(10_000L, s.smoothPositionMs(1_005_000))
    }

    @Test
    fun `playback speed scales the extrapolation`() {
        assertEquals(10_200L, state(speed = 2f).smoothPositionMs(1_000_100))
        assertEquals(10_050L, state(speed = 0.5f).smoothPositionMs(1_000_100))
    }

    @Test
    fun `a stalled loop cannot run the clock away`() {
        // The gate should stop the loop long before this, but if a sample goes stale the position must
        // not sprint to the end of the song — one second of guessing is already generous.
        assertEquals(11_000L, state().smoothPositionMs(1_060_000))
    }

    @Test
    fun `a clock reading from before the sample never rewinds the position`() {
        assertEquals(10_000L, state().smoothPositionMs(999_000))
    }

    @Test
    fun `the position is clamped to the track duration`() {
        val s = state(positionMs = 199_900, durationMs = 200_000)

        assertEquals(200_000L, s.smoothPositionMs(1_000_500))
    }

    @Test
    fun `an unstamped state is used as-is`() {
        // Nothing has pushed a sample yet (the default PlaybackState). Extrapolating from epoch zero
        // would put the song hours in.
        assertEquals(10_000L, state(sampledAt = 0).smoothPositionMs(1_000_000))
    }
}
