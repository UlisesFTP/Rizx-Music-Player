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
 * A second list, [RegistryPlugin.NOT_RUNNABLE], hides the other kind: a plugin with no way to reach
 * the host at all. Kept apart because the two stop being true for different reasons — one when Rizx
 * drops a native provider, the other when the missing half of the runtime gets built.
 *
 * These assert the *list*, not a label, because that is now where the decision lives.
 */
class PluginStoreVisibilityTest {

    /** Through the production predicate, not the raw set — the store now hides by category too. */
    private fun hidden(id: String, category: String = "metadata") =
        RegistryPlugin(id = id, repo = "nukeop/x", category = category).isHidden

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
        //
        // SoundCloud Dashboard is the close call and it stays: the native provider declares TOP_TRACKS
        // and nothing else, while the plugin also brings editorial picks and a signed-in user's likes,
        // follows and recommendations. Hiding a superset would cost capability, not remove duplication.
        listOf(
            "nuclear-plugin-discogs",
            "nuclear-plugin-musicbrainz",
            "nuclear-plugin-bandcamp",
            "nuclear-plugin-bandcamp-dashboard",
            "nuclear-plugin-listenbrainz-dashboard",
            "nuclear-plugin-soundcloud-dashboard",
            "nuclear-plugin-khinsider",
            "nuclear-plugin-youtube-liked-songs-sync",
        ).forEach { assertFalse(it, hidden(it)) }
    }

    @Test
    fun `a plugin that could not run here is hidden for that reason, not the native one`() {
        // Last.fm registers a `scrobbling` descriptor, which buildProvider does not map, and the host
        // never calls `rizx.emit`, so its api.Events subscription would never fire either. It installs
        // and does nothing.
        assertTrue(hidden("nuclear-plugin-lastfm"))
        assertTrue("nuclear-plugin-lastfm" in RegistryPlugin.NOT_RUNNABLE)
        // The two reasons stay apart: this one becomes listable by building the missing half, whereas
        // the native six become listable only by removing something Rizx already does.
        assertFalse("Rizx has no native scrobbling", "nuclear-plugin-lastfm" in RegistryPlugin.REPLACED_BY_NATIVE)
    }

    @Test
    fun `a category the runtime cannot dispatch is hidden, whatever the id`() {
        // The same rule as NOT_RUNNABLE, stated generally: `buildProvider` maps six kinds, and a plugin
        // outside them registers nothing. Expressed as a category so a *future* scrobbling or discovery
        // plugin is kept out without anyone noticing it and adding its id by hand.
        assertTrue(hidden("some-new-scrobbler", category = "scrobbling"))
        assertTrue(hidden("some-new-recommender", category = "discovery"))
        // Discovery is the subtler one: the runtime does build a provider, but nothing in the app ever
        // calls it — the up-next engine is a closed set. It would install and look perfectly healthy.
        assertTrue(hidden("x", category = "Discovery"))
    }

    @Test
    fun `the categories the app does dispatch stay listed`() {
        listOf("metadata", "streaming", "lyrics", "dashboard", "playlists", "other")
            .forEach { assertFalse(it, hidden("some-third-party-plugin", category = it)) }
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
