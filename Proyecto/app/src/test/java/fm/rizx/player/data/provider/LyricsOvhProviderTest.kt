package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.lyricsovh.LyricsOvhApi
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class LyricsOvhProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var api: LyricsOvhApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(LyricsOvhApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun provider() = LyricsOvhProvider(api, io = Dispatchers.Unconfined)

    private fun track(artist: String? = "Coldplay") = Track(
        title = "Yellow",
        artists = artist?.let { listOf(ArtistCredit(name = it)) } ?: emptyList(),
        source = ProviderRef("itunes", "1"),
    )

    @Test
    fun `returns prose on success, never timed lines`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"lyrics":"Look at the stars\nHow they shine for you"}"""))

        val lyrics = provider().getLyrics(track())

        assertTrue(lyrics!!.plain!!.contains("Look at the stars"))
        // This API has no timestamps: the synced view must never think it does.
        assertFalse(lyrics.isSynced)
    }

    @Test
    fun `a 404 means no lyrics, not an error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"No lyrics found"}"""))

        assertNull(provider().getLyrics(track()))
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
    fun `blank lyrics are treated as none`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"lyrics":"   "}"""))

        assertNull(provider().getLyrics(track()))
    }
}
