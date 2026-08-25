package fm.rizx.player.data.provider

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The URL-file importer, and since spec 021 the importer for Rizx share links. A share URL is
 * `<shareBaseUrl>/<43-char token>` — no file extension — so before the shareBaseUrl rule existed no
 * provider matched it and pasting a link failed as "Error al importar" without a single request.
 */
class RizxUrlPlaylistProviderTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun provider(shareBase: String = "", readEndpoint: String = "") =
        RizxUrlPlaylistProvider(OkHttpClient(), shareBaseUrl = shareBase, shareReadEndpoint = readEndpoint)

    private val token = "iS_UBbQ-InTyjHsGRcBwCk7s1tWiK_qJSv8np62mxLY"

    @Test
    fun `handles a share link when the share base is configured`() {
        val base = "https://example.supabase.co/functions/v1/playlist-shares"
        assertTrue(provider(base).canHandle("$base/$token"))
        // Trailing slash or case differences in config must not break the match.
        assertTrue(provider("$base/").canHandle("$base/$token"))
        assertTrue(provider(base.uppercase()).canHandle("$base/$token"))
    }

    @Test
    fun `share links stay unhandled when no share backend is configured`() {
        assertFalse(provider().canHandle("https://example.supabase.co/functions/v1/playlist-shares/$token"))
        // And an unrelated URL on the same host is not claimed by the share rule.
        val base = "https://example.supabase.co/functions/v1/playlist-shares"
        assertFalse(provider(base).canHandle("https://example.supabase.co/functions/v1/sync"))
    }

    @Test
    fun `still handles plain playlist files`() {
        val p = provider("https://example.supabase.co/functions/v1/playlist-shares")
        assertTrue(p.canHandle("https://host/lists/mine.json"))
        assertTrue(p.canHandle("https://raw.githubusercontent.com/u/r/main/list.csv"))
        assertFalse(p.canHandle("https://open.spotify.com/playlist/abc"))
    }

    @Test
    fun `fetches and decodes the shared document`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"format":"rizx.playlist","version":2,"name":"Compartida","description":"de un amigo","items":[]}""",
            ),
        )
        val base = server.url("/functions/v1/playlist-shares").toString()
        val preview = provider(base).fetchPlaylist("$base/$token")
        assertEquals("Compartida", preview.name)
        assertEquals("de un amigo", preview.description)
        assertEquals("/functions/v1/playlist-shares/$token", server.takeRequest().path)
    }

    @Test
    fun `a share link is read from the function, not from its own address`() = runTest {
        server.enqueue(MockResponse().setBody("""{"format":"rizx.playlist","version":2,"name":"Via dominio","items":[]}"""))
        // The link lives on a share domain that serves a landing page to browsers; the document lives
        // at the function. Only the endpoint is ever asked.
        val p = provider(shareBase = "https://rizx.example/s", readEndpoint = server.url("/functions/v1/playlist-shares").toString())

        val preview = p.fetchPlaylist("https://rizx.example/s/$token")

        assertEquals("Via dominio", preview.name)
        val request = server.takeRequest()
        assertEquals("/functions/v1/playlist-shares/$token", request.path)
        assertEquals("the function serves HTML to browsers, so say JSON", "application/json", request.getHeader("Accept"))
    }

    @Test
    fun `plain playlist files are still fetched from where they are`() = runTest {
        server.enqueue(MockResponse().setBody("""{"format":"rizx.playlist","version":2,"name":"Archivo","items":[]}"""))
        val p = provider(shareBase = "https://rizx.example/s", readEndpoint = "https://unused.example/functions/v1/playlist-shares")

        val preview = p.fetchPlaylist(server.url("/lists/mine.json").toString())

        assertEquals("Archivo", preview.name)
        assertEquals("/lists/mine.json", server.takeRequest().path)
    }
}
