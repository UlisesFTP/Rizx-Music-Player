package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Spotify import via the public embed page. The fixture mirrors the **real** `__NEXT_DATA__` shape
 * captured from `open.spotify.com/embed/playlist/…`: `props.pageProps.state.data.entity` with a
 * `trackList` of `{uri, title, subtitle, duration}` (duration in **milliseconds**).
 */
class SpotifyPlaylistProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun provider() = SpotifyPlaylistProvider(
        client = OkHttpClient(),
        json = Json { ignoreUnknownKeys = true; isLenient = true },
        io = Dispatchers.Unconfined,
        embedUrlTemplate = server.url("/embed/playlist/").toString() + "%s",
    )

    private fun embedHtml(tracks: String, name: String = "Today’s Top Hits"): String =
        """<!DOCTYPE html><html><body><div>markup</div>
           <script id="__NEXT_DATA__" type="application/json">
           {"props":{"pageProps":{"state":{"data":{"entity":
             {"type":"playlist","name":"$name","subtitle":"Spotify","trackList":[$tracks]}
           }}}}}
           </script></body></html>
        """.trimIndent()

    private fun trackJson(id: String, title: String, artists: String, durationMs: Long) =
        """{"uri":"spotify:track:$id","title":"$title","subtitle":"$artists","duration":$durationMs,"isPlayable":true}"""

    @Test
    fun `canHandle accepts spotify playlist links and rejects others`() {
        val p = provider()
        assertTrue(p.canHandle("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"))
        assertTrue(p.canHandle("https://open.spotify.com/intl-es/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertTrue(p.canHandle("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertFalse(p.canHandle("https://open.spotify.com/track/65DbTqJKhbwqYbZ1Okr0rc")) // not a playlist
        assertFalse(p.canHandle("https://www.deezer.com/playlist/123"))
    }

    @Test
    fun `reads the embed tracklist into domain tracks`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                embedHtml(
                    listOf(
                        trackJson("65DbTqJKhbwqYbZ1Okr0rc", "Choosin' Texas", "Ella Langley", 232226),
                        trackJson("2plbrEY59IikOBgBGLjaoe", "Dai Dai", "Shakira, Burna Boy", 180000),
                    ).joinToString(","),
                ),
            ),
        )

        val preview = provider().fetchPlaylist("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")

        assertEquals("Today’s Top Hits", preview.name)
        assertEquals(listOf("Choosin' Texas", "Dai Dai"), preview.tracks.map { it.title })
        assertEquals(232226L, preview.tracks[0].durationMs) // duration is already ms
        // Identity is the Spotify track id; audio resolves later by artist+title.
        assertEquals("spotify:65DbTqJKhbwqYbZ1Okr0rc", preview.tracks[0].source.identityKey)
        // Comma-joined artists are split so the first stays clean for the streaming search.
        assertEquals(listOf("Shakira", "Burna Boy"), preview.tracks[1].artists.map { it.name })
    }

    @Test
    fun `flags the embed track cap in the description`() = runBlocking {
        val many = (1..SpotifyPlaylistProvider.EMBED_TRACK_CAP)
            .joinToString(",") { trackJson("id%022d".format(it), "Song $it", "Artist", 1000) }
        server.enqueue(MockResponse().setResponseCode(200).setBody(embedHtml(many)))

        val preview = provider().fetchPlaylist("https://open.spotify.com/playlist/abc")

        assertEquals(SpotifyPlaylistProvider.EMBED_TRACK_CAP, preview.tracks.size)
        assertTrue(preview.description!!.contains("First ${SpotifyPlaylistProvider.EMBED_TRACK_CAP} tracks"))
    }

    @Test
    fun `a page without the data blob fails as a typed provider error`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>nothing here</body></html>"))

        val error = assertThrows(AppError::class.java) {
            runBlocking { provider().fetchPlaylist("https://open.spotify.com/playlist/abc") }
        }
        assertTrue(error is AppError.ProviderFailure)
    }

    @Test
    fun `an http error surfaces as a typed error`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val error = assertThrows(AppError::class.java) {
            runBlocking { provider().fetchPlaylist("https://open.spotify.com/playlist/abc") }
        }
        assertTrue(error is AppError.Network || error is AppError.ProviderFailure)
    }
}
