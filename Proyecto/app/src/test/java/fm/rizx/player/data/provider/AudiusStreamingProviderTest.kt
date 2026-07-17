package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.audius.AudiusApi
import fm.rizx.player.data.remote.audius.AudiusHostProvider
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Audius streaming provider against a fake HTTP server (host discovery + track search + stream URL). */
class AudiusStreamingProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AudiusApi
    private lateinit var base: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        base = server.url("/").toString().trimEnd('/')
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AudiusApi::class.java)
    }

    @After
    fun tearDown() { runCatching { server.shutdown() } }

    private fun provider() = AudiusStreamingProvider(
        api,
        AudiusHostProvider(api, discoveryUrl = server.url("/").toString(), fallbackHost = base),
        io = Dispatchers.Unconfined,
    )

    private fun hostsResponse() = MockResponse().setResponseCode(200).setBody("""{"data":["$base"]}""")

    private fun track() = Track(
        title = "GIRLS", artists = listOf(ArtistCredit("Luci")), source = ProviderRef("itunes", "1"),
    )

    @Test
    fun `searchForTrack discovers a host then maps candidates`() = runBlocking {
        server.enqueue(hostsResponse())
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"VPEA2ka","title":"GIRLS","duration":105}]}"""))

        val candidates = provider().searchForTrack(track())

        assertEquals(1, candidates.size)
        assertEquals("VPEA2ka", candidates.first().id)
    }

    @Test
    fun `drops fuzzy rows whose title does not match the requested track`() = runBlocking {
        server.enqueue(hostsResponse())
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"x1","title":"Totally Different Song","user":{"name":"Someone"}}]}"""))

        assertTrue(provider().searchForTrack(track()).isEmpty())
    }

    @Test
    fun `drops a same-title row by a different artist`() = runBlocking {
        server.enqueue(hostsResponse())
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"nope","title":"GIRLS","user":{"name":"Some Other Band"}}]}"""))

        assertTrue(provider().searchForTrack(track()).isEmpty())
    }

    @Test
    fun `drops non-streamable rows`() = runBlocking {
        server.enqueue(hostsResponse())
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"x1","title":"GIRLS","is_streamable":false}]}"""))

        assertTrue(provider().searchForTrack(track()).isEmpty())
    }

    @Test
    fun `keeps a row when both title and artist match`() = runBlocking {
        server.enqueue(hostsResponse())
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"ok1","title":"GIRLS","user":{"name":"Luci"}}]}"""))

        assertEquals("ok1", provider().searchForTrack(track()).single().id)
    }

    @Test
    fun `getStreamUrl builds a full-length stream url on the discovered host`() = runBlocking {
        server.enqueue(hostsResponse())

        val stream = provider().getStreamUrl(StreamCandidate(id = "VPEA2ka", title = "GIRLS", source = ProviderRef("audius-streaming", "VPEA2ka")))

        assertTrue(stream.url.startsWith(base))
        assertTrue(stream.url.endsWith("/v1/tracks/VPEA2ka/stream?app_name=RizxPlayer"))
    }

    @Test
    fun `a server error surfaces as a typed AppError`() {
        server.enqueue(hostsResponse())
        server.enqueue(MockResponse().setResponseCode(500))

        val error = assertThrows(AppError::class.java) { runBlocking { provider().searchForTrack(track()) } }
        assertTrue(error is AppError.ProviderFailure)
    }

    @Test
    fun `no connectivity surfaces as AppError_Network`() {
        server.shutdown() // nothing listening on the port → ConnectException (an IOException)

        val error = assertThrows(AppError::class.java) { runBlocking { provider().searchForTrack(track()) } }
        assertTrue(error is AppError.Network)
    }
}
