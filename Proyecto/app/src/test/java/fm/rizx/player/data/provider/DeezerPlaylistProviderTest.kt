package fm.rizx.player.data.provider

import fm.rizx.player.data.remote.deezer.DeezerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class DeezerPlaylistProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var api: DeezerApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DeezerApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun provider() = DeezerPlaylistProvider(api, io = Dispatchers.Unconfined)

    @Test
    fun `canHandle matches deezer playlist urls only`() {
        val p = provider()
        assertTrue(p.canHandle("https://www.deezer.com/en/playlist/908622995"))
        assertTrue(p.canHandle("https://api.deezer.com/playlist/123"))
        assertFalse(p.canHandle("https://open.spotify.com/playlist/abc"))
        assertFalse(p.canHandle("https://deezer.com/album/302127"))
    }

    @Test
    fun `fetchPlaylist maps the tracklist`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":908622995,"title":"Top France","description":"hits","tracks":{"data":[{"id":1,"title":"One More Time","duration":320,"artist":{"id":27,"name":"Daft Punk"}}]}}"""))

        val preview = provider().fetchPlaylist("https://www.deezer.com/playlist/908622995")

        assertEquals("Top France", preview.name)
        assertEquals(listOf("One More Time"), preview.tracks.map { it.title })
        assertEquals("playlist:908622995", preview.origin?.id)
    }
}
