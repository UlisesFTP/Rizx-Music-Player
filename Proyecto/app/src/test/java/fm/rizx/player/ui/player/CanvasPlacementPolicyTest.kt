package fm.rizx.player.ui.player

import fm.rizx.player.domain.model.CanvasNetworkPolicy
import fm.rizx.player.domain.model.CanvasPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasPlacementPolicyTest {

    @Test
    fun `master off blocks every placement`() {
        val preferences = CanvasPreferences(enabled = false)

        assertNull(canvasPreferencesFor(preferences, CanvasPlacement.NOW_PLAYING, motionEnabled = true))
        assertNull(canvasPreferencesFor(preferences, CanvasPlacement.HOME_HERO, motionEnabled = true))
    }

    @Test
    fun `home requires motion and tightens network and battery policy`() {
        val permissive = CanvasPreferences(
            enabled = true,
            network = CanvasNetworkPolicy.ANY,
            allowOnBatterySaver = true,
        )

        assertNull(canvasPreferencesFor(permissive, CanvasPlacement.HOME_HERO, motionEnabled = false))
        val home = canvasPreferencesFor(permissive, CanvasPlacement.HOME_HERO, motionEnabled = true)!!
        assertEquals(CanvasNetworkPolicy.UNMETERED_ONLY, home.network)
        assertFalse(home.allowOnBatterySaver)
    }

    @Test
    fun `now playing keeps its persisted policy`() {
        val preferences = CanvasPreferences(
            enabled = true,
            network = CanvasNetworkPolicy.ANY,
            allowOnBatterySaver = true,
        )

        assertEquals(preferences, canvasPreferencesFor(preferences, CanvasPlacement.NOW_PLAYING, true))
        assertNull(
            canvasPreferencesFor(
                preferences.copy(showOnNowPlaying = false),
                CanvasPlacement.NOW_PLAYING,
                true,
            ),
        )
    }
}
