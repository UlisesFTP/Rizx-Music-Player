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
import okhttp3.mockwebserver.QueueDispatcher
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
        // A request nothing was enqueued for answers 404 instead of blocking the test: the majority-
        // script check adds a search after a Latin-only hit, and most tests here don't care about it.
        (server.dispatcher as QueueDispatcher).setFailFast(true)
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
        assertTrue(server.takeRequest().path!!.contains("duration="))
        // The retry must drop the duration, or it would just 404 again.
        assertTrue(!server.takeRequest().path!!.contains("duration="))
    }

    @Test
    fun `the duration-free lookup keeps the words of a far-off cut but not its timings`() = runBlocking {
        // 369 s track; LRCLIB's only copy is a 6:59 extended cut. Right words, wrong clock: the
        // timings would walk out of step, so they go, and the match is marked as the stretch it is.
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(synced = "[00:02.00] long cut", plain = "long cut", duration = 419.0)))

        val lyrics = provider().getLyrics(track())!!

        assertTrue(lyrics.lines.isEmpty())
        assertEquals("long cut", lyrics.plain)
        assertEquals(50_000L, lyrics.matchScore)
    }

    @Test
    fun `an exact hit carries no match score, because there was no matching to do`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(synced = "[00:31.48] Like the legend")))

        assertEquals(null, provider().getLyrics(track())!!.matchScore)
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
        // 368 s against our 369 s: a certain match, and the score says so.
        assertEquals(1_000L, lyrics.matchScore)
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

    private fun row(id: Long, text: String, duration: Double = 367.0, title: String = "Get Lucky") =
        """{"id":$id,"trackName":"$title","artistName":"Daft Punk","albumName":"RAM","duration":$duration,""" +
            """"instrumental":false,"syncedLyrics":"[00:01.00] $text"}"""

    @Test
    fun `a romanized hit is outvoted by the script most uploaders used`() = runBlocking {
        // The exact lookup returned the one romanized row; the search shows three Hangul rows for the
        // same recording. The listener wanted the words, not a transliteration of them.
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(synced = "[00:01.00] haru-ui kkeuteul deuriun")))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "[" + row(1, "haru-ui kkeuteul deuriun") + "," + row(2, "하루의 끝을 드리운 (오)", 368.0) +
                    "," + row(3, "하루의 끝을 드리운", 369.0) + "," + row(4, "하루의 끝을 드리운 (오)", 366.0) + "]",
            ),
        )

        val lyrics = provider().getLyrics(track())!!

        assertEquals("하루의 끝을 드리운", lyrics.lines.first().text)
        // The closest of the Hangul rows: 369 s against our 369 s.
        assertEquals(0L, lyrics.matchScore)
    }

    @Test
    fun `a Latin hit stays when Latin rows are the majority`() = runBlocking {
        // An English song with one Chinese translation uploaded under its name.
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(synced = "[00:01.00] Like the legend")))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "[" + row(1, "Like the legend") + "," + row(2, "Like the legend of the phoenix", 368.0) +
                    "," + row(3, "就像凤凰的传说", 369.0) + "]",
            ),
        )

        assertEquals("Like the legend", provider().getLyrics(track())!!.lines.first().text)
    }

    @Test
    fun `a foreign-script hit is never second-guessed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(synced = "[00:01.00] 하루의 끝을 드리운")))

        provider().getLyrics(track())

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a row with no usable timings neither votes nor gets chosen`() = runBlocking {
        // LRCLIB rows can carry an empty syncedLyrics next to Hangul prose. Two of those must not
        // outvote the timed Latin row, and the swap must never hand back prose for timings.
        val prose = """{"id":9,"trackName":"Get Lucky","artistName":"Daft Punk","albumName":"RAM","duration":369.0,""" +
            """"instrumental":false,"plainLyrics":"하루의 끝을 드리운","syncedLyrics":""}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(synced = "[00:01.00] Yeah, yeah")))
        server.enqueue(MockResponse().setResponseCode(200).setBody("[" + row(1, "Yeah, yeah") + "," + prose + "," + prose + "]"))

        val lyrics = provider().getLyrics(track())!!

        assertEquals("Yeah, yeah", lyrics.lines.first().text)
    }

    @Test
    fun `only rows that are this recording get a vote`() = runBlocking {
        // Two Japanese rows of a *live* version don't outvote the studio row we were given.
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(synced = "[00:01.00] Like the legend")))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "[" + row(1, "Like the legend") + "," + row(2, "伝説のように", 368.0, title = "Get Lucky (Live)") +
                    "," + row(3, "伝説のように", 369.0, title = "Get Lucky (Live)") + "]",
            ),
        )

        assertEquals("Like the legend", provider().getLyrics(track())!!.lines.first().text)
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
