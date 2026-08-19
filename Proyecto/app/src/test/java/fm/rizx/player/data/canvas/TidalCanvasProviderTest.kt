package fm.rizx.player.data.canvas

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.CanvasAspect
import fm.rizx.player.domain.model.CanvasQuality
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * TIDAL's animated album covers. The fixtures mirror the real search response shape (verified live:
 * `tracks.items[].album.videoCover`, durations in seconds, an `artists` array as the billing), and
 * everything rides through MockWebServer because the endpoint is best-effort — the tests must keep
 * meaning something the day TIDAL changes it.
 */
class TidalCanvasProviderTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    private val cover = "e9e126a9-97b6-4f99-b7b6-c242cb6eb31d"
    private val coverUrl = "https://resources.tidal.com/videos/e9e126a9/97b6/4f99/b7b6/c242cb6eb31d/1280x1280.mp4"

    private fun provider(country: String = "MX") =
        TidalCanvasProvider(client, countryCode = { country }, baseUrl = server.url("/v1/").toString())

    private fun track(
        title: String = "Blinding Lights",
        artist: String = "The Weeknd",
        album: String? = "After Hours",
    ) = Track(
        title = title,
        artists = listOf(ArtistCredit(artist)),
        durationMs = 200_040,
        album = album?.let { AlbumRef(title = it, source = ProviderRef("deezer", "al")) },
        source = ProviderRef("deezer", "1"),
    )

    private fun trackRow(
        title: String = "Blinding Lights",
        artist: String = "The Weeknd",
        durationSeconds: Long = 200,
        albumTitle: String = "After Hours",
        videoCover: String? = cover,
    ): String {
        val vc = videoCover?.let { "\"$it\"" } ?: "null"
        return """{"title":"$title","duration":$durationSeconds,"replayGain":-11.0,
            "artists":[{"id":1,"name":"$artist","type":"MAIN"}],
            "album":{"id":2,"title":"$albumTitle","videoCover":$vc}}"""
    }

    private fun tracksBody(vararg rows: String) =
        """{"tracks":{"limit":10,"offset":0,"totalNumberOfItems":${rows.size},"items":[${rows.joinToString(",")}]}}"""

    private fun albumRow(title: String, artist: String?, videoCover: String? = cover): String {
        val vc = videoCover?.let { "\"$it\"" } ?: "null"
        val artists = artist?.let { """[{"id":1,"name":"$it"}]""" } ?: "[]"
        return """{"title":"$title","artists":$artists,"videoCover":$vc}"""
    }

    private fun albumsBody(vararg rows: String) =
        """{"albums":{"limit":10,"offset":0,"totalNumberOfItems":${rows.size},"items":[${rows.joinToString(",")}]}}"""

    private fun resolve(track: Track = track()) = runBlocking {
        provider().resolve(track, CanvasAspect.PORTRAIT, CanvasQuality.AUTO)
    }

    // ---- formatVideoUrl ----

    @Test
    fun `formatVideoUrl builds the CDN path from five segments`() {
        assertEquals(coverUrl, TidalCanvasProvider.formatVideoUrl(cover))
    }

    @Test
    fun `formatVideoUrl refuses every other shape`() {
        assertNull(TidalCanvasProvider.formatVideoUrl(""))
        assertNull(TidalCanvasProvider.formatVideoUrl("a-b-c-d"))          // 4 segments
        assertNull(TidalCanvasProvider.formatVideoUrl("a-b-c-d-e-f"))      // 6 segments
        assertNull(TidalCanvasProvider.formatVideoUrl("a-b--d-e"))         // empty segment
    }

    // ---- track search ----

    @Test
    fun `a matching track with a videoCover becomes a square mp4 candidate`() {
        server.enqueue(MockResponse().setBody(tracksBody(trackRow())))
        val candidate = resolve().single()
        assertEquals("tidal", candidate.providerId)
        assertEquals(coverUrl, candidate.mediaUrl)
        assertEquals("video/mp4", candidate.mimeType)
        assertEquals(CanvasAspect.SQUARE, candidate.aspect)   // PORTRAIT hint doesn't reject it
        assertEquals(1280, candidate.width)
        assertEquals(1280, candidate.height)
        assertNull(candidate.expiresAtMs)
        assertEquals(200_000L, candidate.durationMs)          // seconds from the wire, ms in the model
        assertTrue(candidate.score > 0)

        val request = server.takeRequest()
        assertEquals("MX", request.requestUrl?.queryParameter("countryCode"))
        assertEquals("TRACKS", request.requestUrl?.queryParameter("types"))
        // No album in the track query: extra words act as constraints and can hide the track itself.
        assertEquals("The Weeknd Blinding Lights", request.requestUrl?.queryParameter("query"))
        assertTrue(request.getHeader("X-Tidal-Token")?.isNotBlank() == true)
    }

    @Test
    fun `the wrong top result loses to the right second one`() {
        server.enqueue(
            MockResponse().setBody(
                tracksBody(
                    trackRow(artist = "Marching Band Covers", videoCover = "aa-bb-cc-dd-ee"),
                    trackRow(),
                ),
            ),
        )
        assertEquals(coverUrl, resolve().first().mediaUrl)
    }

    @Test
    fun `a different artist or a different version is nobody's canvas`() {
        // Each rejected track search falls through to the album search — feed that an empty page too.
        server.enqueue(MockResponse().setBody(tracksBody(trackRow(artist = "Someone Else"))))
        server.enqueue(MockResponse().setBody(albumsBody()))
        assertTrue(resolve().isEmpty())
        server.enqueue(MockResponse().setBody(tracksBody(trackRow(title = "Blinding Lights (Live)"))))
        server.enqueue(MockResponse().setBody(albumsBody()))
        assertTrue(resolve().isEmpty())
    }

    @Test
    fun `several valid rows come back best first as spares`() {
        server.enqueue(
            MockResponse().setBody(
                tracksBody(
                    trackRow(durationSeconds = 230, videoCover = "11-22-33-44-55"), // drifted → lower score
                    trackRow(),                                                     // exact duration
                ),
            ),
        )
        val candidates = resolve()
        assertEquals(2, candidates.size)
        assertEquals(coverUrl, candidates.first().mediaUrl)
        assertTrue(candidates.first().score > candidates.last().score)
    }

    @Test
    fun `a match without videoCover is a miss, not an error`() {
        server.enqueue(MockResponse().setBody(tracksBody(trackRow(videoCover = null))))
        server.enqueue(MockResponse().setBody(albumsBody()))
        assertTrue(resolve().isEmpty())
    }

    // ---- album fallback ----

    @Test
    fun `the album search rescues a cover the track search missed`() {
        server.enqueue(MockResponse().setBody(tracksBody()))
        server.enqueue(MockResponse().setBody(albumsBody(albumRow("After Hours", "The Weeknd"))))
        val candidate = resolve().single()
        assertEquals(coverUrl, candidate.mediaUrl)
        assertEquals("tidal", candidate.providerId)

        server.takeRequest()
        val second = server.takeRequest()
        assertEquals("ALBUMS", second.requestUrl?.queryParameter("types"))
        assertEquals("After Hours The Weeknd", second.requestUrl?.queryParameter("query"))
    }

    @Test
    fun `the album fallback rejects other editions and other artists`() {
        server.enqueue(MockResponse().setBody(tracksBody()))
        server.enqueue(
            MockResponse().setBody(
                albumsBody(
                    albumRow("After Hours (Live)", "The Weeknd"),
                    albumRow("After Hours", "A Tribute Band"),
                    albumRow("After Hours", artist = null),
                ),
            ),
        )
        assertTrue(resolve().isEmpty())
    }

    @Test
    fun `no album on the track means no second request`() {
        server.enqueue(MockResponse().setBody(tracksBody()))
        assertTrue(resolve(track(album = null)).isEmpty())
        assertEquals(1, server.requestCount)
    }

    // ---- errors stay errors ----

    @Test
    fun `http failures and malformed json throw instead of pretending to be a miss`() {
        server.enqueue(MockResponse().setResponseCode(429))
        assertThrows(IOException::class.java) { resolve() }
        server.enqueue(MockResponse().setResponseCode(500))
        assertThrows(IOException::class.java) { resolve() }
        server.enqueue(MockResponse().setBody("<html>maintenance</html>"))
        assertThrows(Exception::class.java) { resolve() }
    }

    // ---- country code ----

    @Test
    fun `defaultCountryCode falls back to US when the locale has no country`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("es", "MX"))
            assertEquals("MX", TidalCanvasProvider.defaultCountryCode())
            java.util.Locale.setDefault(java.util.Locale("eo")) // Esperanto: no country at all
            assertEquals("US", TidalCanvasProvider.defaultCountryCode())
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
