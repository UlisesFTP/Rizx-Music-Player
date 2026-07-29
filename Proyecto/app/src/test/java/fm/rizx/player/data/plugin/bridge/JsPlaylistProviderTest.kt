package fm.rizx.player.data.plugin.bridge

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `canHandle` must be cheap and synchronous while the plugin's own `matchesUrl` is async JS, so it
 * guesses from the descriptor id. What matters is that the guess is **narrow**: claiming a URL that
 * belongs to another provider would hand a Deezer link to a SoundCloud plugin, and the import would
 * fail with a confusing error instead of just working.
 */
class JsPlaylistProviderTest {

    private fun provider(descriptorId: String, pluginId: String) = JsPlaylistProvider(
        id = "$pluginId:$descriptorId",
        name = "Test",
        version = "1.0",
        pluginId = pluginId,
        uid = "$pluginId:$descriptorId",
        descriptorId = descriptorId,
        methods = setOf("matchesUrl", "fetchPlaylistByUrl"),
        invoker = object : JsProviderInvoker {
            override suspend fun invoke(uid: String, method: String, argsJson: String, timeoutMs: Long) = null
        },
        json = Json,
    )

    @Test
    fun `it claims its own service's links`() {
        val soundcloud = provider("soundcloud-playlists", "nuclear-plugin-soundcloud")

        assertTrue(soundcloud.canHandle("https://soundcloud.com/artist/sets/mix"))
        assertTrue(soundcloud.canHandle("https://SoundCloud.com/artist/sets/mix")) // case-insensitive
    }

    @Test
    fun `it does not claim another service's links`() {
        val soundcloud = provider("soundcloud-playlists", "nuclear-plugin-soundcloud")

        assertFalse(soundcloud.canHandle("https://www.deezer.com/playlist/123"))
        assertFalse(soundcloud.canHandle("https://open.spotify.com/playlist/abc"))
        assertFalse(soundcloud.canHandle("https://music.youtube.com/playlist?list=X"))
    }

    @Test
    fun `generic words in the id never become a claim`() {
        // Every token here is generic — without the filter this would claim any URL containing
        // "playlist", i.e. essentially every playlist link in existence.
        val vague = provider("playlists", "nuclear-plugin-playlist-provider")

        assertFalse(vague.canHandle("https://www.deezer.com/playlist/123"))
        assertFalse(vague.canHandle("https://open.spotify.com/playlist/abc"))
    }

    @Test
    fun `a non-http string is never claimed`() {
        val soundcloud = provider("soundcloud-playlists", "nuclear-plugin-soundcloud")

        assertFalse(soundcloud.canHandle("soundcloud.com/artist/sets/mix")) // no scheme
        assertFalse(soundcloud.canHandle(""))
    }
}
