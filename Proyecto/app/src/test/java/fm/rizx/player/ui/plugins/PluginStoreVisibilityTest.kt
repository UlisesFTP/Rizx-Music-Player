package fm.rizx.player.ui.plugins

import fm.rizx.player.domain.plugin.InstalledPlugin
import fm.rizx.player.domain.plugin.RegistryPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store's visibility policy (ADR 0019). Plugins that can't run here are **listed with a reason**
 * rather than hidden — a catalogue that silently omits entries can't tell a contributor what is
 * missing, and it made "why isn't X in the store?" unanswerable.
 */
class PluginStoreVisibilityTest {

    private fun entry(id: String) = RegistryPlugin(id = id, repo = "owner/$id")

    @Test
    fun `the yt-dlp plugins are supported again — the runtime backs api Ytdlp natively now`() {
        assertTrue(entry("nuclear-plugin-youtube").isSupported)
        assertTrue(entry("nuclear-plugin-youtube-playlists").isSupported)
        assertTrue(entry("nuclear-plugin-omnisource").isSupported)
    }

    @Test
    fun `last_fm is supported — it registers a discovery provider that needs no login`() {
        assertTrue(entry("nuclear-plugin-lastfm").isSupported)
    }

    @Test
    fun `the desktop media-session plugin is unsupported, and says why`() {
        val mediaSession = entry("nuclear-plugin-mediasession")

        assertFalse(mediaSession.isSupported)
        assertEquals(RegistryPlugin.REASON_NATIVE_EQUIVALENT, mediaSession.unsupportedReason)
    }

    @Test
    fun `an ordinary plugin carries no reason at all`() {
        assertNull(entry("nuclear-plugin-discogs").unsupportedReason)
    }

    @Test
    fun `a quarantined plugin is flagged, and only by that exact health value`() {
        val healthy = InstalledPlugin(id = "a", version = "1", name = "A", dir = "/d", entryPath = "src/index")

        assertFalse(healthy.isQuarantined)
        assertTrue(healthy.copy(health = InstalledPlugin.HEALTH_QUARANTINED).isQuarantined)
        assertFalse(healthy.copy(health = "something-else").isQuarantined)
    }
}
