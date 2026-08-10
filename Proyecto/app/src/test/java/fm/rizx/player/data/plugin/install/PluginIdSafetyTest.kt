package fm.rizx.player.data.plugin.install

import fm.rizx.player.data.plugin.install.PluginInstaller.Companion.isSafePluginId
import fm.rizx.player.data.plugin.install.PluginInstaller.Companion.pluginIdFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plugin id is a directory name, and the installer **deletes that directory** before extracting into
 * it. That makes the id the most dangerous string in the whole plugin path.
 *
 * `pluginIdFor("..")` used to return `".."`, so a manifest named `".."` resolved to the app's entire
 * files directory: installing it wiped the database, the downloads, the playback session and every
 * other plugin. It was reachable by pasting a URL into "Install from URL".
 */
class PluginIdSafetyTest {

    @Test
    fun `traversal and dotfile ids are refused`() {
        listOf("..", ".", "...", ".cache", ".tmp-sideload", ".hidden", "").forEach {
            assertFalse(it, isSafePluginId(it))
        }
        // Through the normalizer too: it is the only thing standing between a manifest and a directory.
        listOf("..", ".", ".cache", "   ", "---").forEach {
            assertEquals("from '$it'", "", pluginIdFor(it))
        }
    }

    @Test
    fun `path separators can never appear in an id`() {
        assertFalse(isSafePluginId("a/b"))
        assertFalse(isSafePluginId("a\\b"))
        // The normalizer already collapses them, but the check does not rely on that: a registry entry
        // arrives as a raw string and is validated on its own.
        assertEquals("a-b", pluginIdFor("a/b"))
    }

    @Test
    fun `ordinary names still normalize the way the store predicts`() {
        assertEquals("rizx-lossless", pluginIdFor("Rizx Lossless"))
        assertEquals("scope-plugin-x", pluginIdFor("@scope/plugin-x"))
        assertEquals("nuclear-plugin-discogs", pluginIdFor("nuclear-plugin-discogs"))
        // A dot inside is fine — only a *leading* one is a directory problem.
        assertTrue(isSafePluginId("next.js-thing"))
    }
}
