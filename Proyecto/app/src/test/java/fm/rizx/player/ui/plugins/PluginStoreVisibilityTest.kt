package fm.rizx.player.ui.plugins

import fm.rizx.player.domain.plugin.InstalledPlugin
import fm.rizx.player.domain.plugin.RegistryPlugin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the store lists, and what it quietly doesn't.
 *
 * ADR 0019 said every entry should be visible, with a reason when it can't run — good policy for a
 * plugin that genuinely cannot work on Android. It is the wrong policy for one that works fine and
 * merely duplicates a native provider: those install a second, worse YouTube next to the built-in one
 * and then compete with it in the streaming chain, and a row captioned "you already have this" is
 * clutter rather than information. So [RegistryPlugin.REPLACED_BY_NATIVE] hides exactly those.
 *
 * These assert the *list*, not a label, because that is now where the decision lives.
 */
class PluginStoreVisibilityTest {

    private fun hidden(id: String) = id in RegistryPlugin.REPLACED_BY_NATIVE

    @Test
    fun `plugins Rizx already does natively are not listed`() {
        assertTrue("Media3's session does this", hidden("nuclear-plugin-mediasession"))
        assertTrue("ADR 0014: native NewPipe YouTube", hidden("nuclear-plugin-youtube"))
        assertTrue("native SoundCloud, also NewPipe", hidden("nuclear-plugin-soundcloud"))
        assertTrue("DeezerDashboardProvider fills Home", hidden("nuclear-plugin-deezer-dashboard"))
        assertTrue("the streaming fallback chain is this", hidden("nuclear-plugin-omnisource"))
        assertTrue("Rizx imports YouTube playlists by URL", hidden("nuclear-plugin-youtube-playlists"))
    }

    @Test
    fun `plugins that add something Rizx lacks stay listed`() {
        // The list has to stop somewhere, and this is where: no native equivalent, so no reason to hide.
        listOf(
            "nuclear-plugin-discogs",
            "nuclear-plugin-musicbrainz",
            "nuclear-plugin-bandcamp",
            "nuclear-plugin-bandcamp-dashboard",
            "nuclear-plugin-listenbrainz-dashboard",
            "nuclear-plugin-khinsider",
            "nuclear-plugin-lastfm",
            "nuclear-plugin-youtube-liked-songs-sync",
        ).forEach { assertFalse(it, hidden(it)) }
    }

    @Test
    fun `an unknown plugin is listed by default`() {
        // A registry the user added themselves must not be filtered by a list written for Nuclear's.
        assertFalse(hidden("some-third-party-plugin"))
    }

    @Test
    fun `a quarantined plugin is flagged, and only by that exact health value`() {
        val healthy = InstalledPlugin(id = "a", version = "1", name = "A", dir = "/d", entryPath = "src/index")

        assertFalse(healthy.isQuarantined)
        assertTrue(healthy.copy(health = InstalledPlugin.HEALTH_QUARANTINED).isQuarantined)
        assertFalse(healthy.copy(health = "something-else").isQuarantined)
    }
}
