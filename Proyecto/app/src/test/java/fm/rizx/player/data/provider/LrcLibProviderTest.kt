package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.lrclib.LrcLibApi
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class LrcLibProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var api: LrcLibApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(LrcLibApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun provider() = LrcLibProvider(api, io = Dispatchers.Unconfined)

    private fun track(durationMs: Long? = 369_000L, artist: String? = "Daft Punk") = Track(
        title = "Get Lucky",
        artists = artist?.let { listOf(ArtistCredit(name = it)) } ?: emptyList(),
        durationMs = durationMs,
        source = ProviderRef("deezer", "1"),
    )

    private fun body(synced: String? = null, plain: String? = null, duration: Double? = 367.0, id: Long = 1) =
        buildString {
            append("""{"id":$id,"trackName":"Get Lucky","artistName":"Daft Punk","albumName":"RAM"""")
            append(""","duration":$duration,"instrumental":false""")
            plain?.let { append(""","plainLyrics":"$it"""") }
            synced?.let { append(""","syncedLyrics":"$it"""") }
            append("}")
        }

    @Test
    fun `parses timed lyrics from the exact lookup`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(synced = "[00:31.48] Like the legend")))

        val lyrics = provider().getLyrics(track())

        assertTrue(lyrics!!.isSynced)
        assertEquals(31_480L, lyrics.lines.first().timeMs)
        assertEquals("LRCLIB", lyrics.sourceName)
    }

    @Test
    fun `sends our duration in seconds so the exact match can apply`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(synced = "[00:01.00] a")))

        provider().getLyrics(track(durationMs = 369_400L))

        val path = server.takeRequest().path!!
        assertTrue(path, path.contains("duration=369"))
    }

    @Test
    fun `a duration mismatch falls back to the duration-free lookup`() = runBlocking {
        // LRCLIB matches duration within 2s and 404s otherwise — routine for YouTube-sourced audio.
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"name":"TrackNotFound"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(synced = "[00:02.00] second try")))

        val lyrics = provider().getLyrics(track())

        assertEquals("second try", lyrics!!.lines.first().text)
        assertEquals(2, server.requestCount)
        assertTrue(server.takeRequest().path!!.contains("duration="))
        // The retry must drop the duration, or it would just 404 again.
        assertTrue(!server.takeRequest().path!!.contains("duration="))
    }

    @Test
    fun `when both lookups miss it searches and takes the closest duration`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "[" + body(synced = "[00:01.00] live version", duration = 500.0, id = 1) +
                    "," + body(synced = "[00:01.00] studio version", duration = 368.0, id = 2) + "]",
            ),
        )

        val lyrics = provider().getLyrics(track())

        assertEquals("studio version", lyrics!!.lines.first().text)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `search prefers a timed transcription over a plain one`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "[" + body(plain = "prose only", duration = 369.0, id = 1) +
                    "," + body(synced = "[00:01.00] timed", duration = 372.0, id = 2) + "]",
            ),
        )

        // The plain row is a closer duration match, but timed lyrics are what the screen is for.
        assertEquals("timed", provider().getLyrics(track())!!.lines.first().text)
    }

    @Test
    fun `plain-only results still count as lyrics`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(plain = "Like the legend")))

        val lyrics = provider().getLyrics(track())

        assertEquals("Like the legend", lyrics!!.plain)
        assertTrue(!lyrics.isSynced)
    }

    @Test
    fun `an untranscribed song is a miss, not an error`() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(404).setBody("{}")) }
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        assertNull(provider().getLyrics(track()))
    }

    @Test
    fun `no duration skips the exact lookup entirely`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(synced = "[00:01.00] a")))

        provider().getLyrics(track(durationMs = null))

        assertEquals(1, server.requestCount)
        assertTrue(!server.takeRequest().path!!.contains("duration="))
    }

    @Test
    fun `a track without an artist never hits the network`() = runBlocking {
        assertNull(provider().getLyrics(track(artist = null)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a server error surfaces as a typed AppError`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val error = assertThrows(AppError::class.java) { runBlocking { provider().getLyrics(track()) } }
        assertTrue(error is AppError.ProviderFailure)
    }

    @Test
    fun `searchLyrics maps rows to candidates`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("[" + body(synced = "[00:01.00] a", duration = 367.0, id = 42) + "]"),
        )

        val results = provider().searchLyrics("daft punk get lucky")

        assertEquals(1, results.size)
        assertEquals("42", results[0].id)
        assertEquals("Get Lucky", results[0].title)
        assertEquals("RAM", results[0].album)
        assertEquals(367_000L, results[0].durationMs)
        assertTrue(results[0].lyrics.isSynced)
    }

    @Test
    fun `a blank query never hits the network`() = runBlocking {
        assertTrue(provider().searchLyrics("   ").isEmpty())
        assertEquals(0, server.requestCount)
    }
}
