package fm.rizx.player.playback

import androidx.media3.common.Player
import fm.rizx.player.domain.playback.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStatusMapperTest {

    @Test
    fun `idle state maps to IDLE`() {
        assertEquals(PlaybackStatus.IDLE, playbackStatusOf(Player.STATE_IDLE, playWhenReady = false, hasError = false))
    }

    @Test
    fun `buffering state maps to BUFFERING`() {
        assertEquals(PlaybackStatus.BUFFERING, playbackStatusOf(Player.STATE_BUFFERING, playWhenReady = true, hasError = false))
    }

    @Test
    fun `ready and playWhenReady maps to PLAYING`() {
        assertEquals(PlaybackStatus.PLAYING, playbackStatusOf(Player.STATE_READY, playWhenReady = true, hasError = false))
    }

    @Test
    fun `ready but not playWhenReady maps to PAUSED`() {
        assertEquals(PlaybackStatus.PAUSED, playbackStatusOf(Player.STATE_READY, playWhenReady = false, hasError = false))
    }

    @Test
    fun `ended state maps to ENDED`() {
        assertEquals(PlaybackStatus.ENDED, playbackStatusOf(Player.STATE_ENDED, playWhenReady = false, hasError = false))
    }

    @Test
    fun `an error overrides any state`() {
        assertEquals(PlaybackStatus.ERROR, playbackStatusOf(Player.STATE_READY, playWhenReady = true, hasError = true))
        assertEquals(PlaybackStatus.ERROR, playbackStatusOf(Player.STATE_IDLE, playWhenReady = false, hasError = true))
    }
}
