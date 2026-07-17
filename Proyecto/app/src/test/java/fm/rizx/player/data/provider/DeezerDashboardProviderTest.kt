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
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class DeezerDashboardProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var api: DeezerApi

    private val chartBody = """
        {"tracks":{"data":[{"id":1,"title":"One More Time","duration":320,"artist":{"id":27,"name":"Daft Punk"}}]},
         "albums":{"data":[{"id":302127,"title":"Discovery","cover_xl":"https://c/xl.jpg"}]},
         "artists":{"data":[{"id":27,"name":"Daft Punk","picture_xl":"https://p/xl.jpg"}]},
         "playlists":{"data":[{"id":900,"title":"Top France","picture_xl":"https://pl/xl.jpg","nb_tracks":50}]}}
    """.trimIndent()

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

    private fun provider() = DeezerDashboardProvider(api, io = Dispatchers.Unconfined, nowMs = { 0L }, ttlMs = 60_000)

    @Test
    fun `chart maps every section`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(chartBody))
        val p = provider()

        assertEquals(listOf("One More Time"), p.topTracks(10).map { it.title })
        assertEquals(listOf("Discovery"), p.topAlbums(10).map { it.title })
        assertEquals(listOf("Daft Punk"), p.topArtists(10).map { it.name })
        assertEquals(listOf("Top France"), p.editorialPlaylists(10).map { it.name })
        assertEquals("album:302127", p.topAlbums(10).first().source.id)
    }

    @Test
    fun `the chart response is memoized across sections`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(chartBody))
        val p = provider()

        p.topTracks(10)
        p.topAlbums(10)
        p.topArtists(10)

        assertEquals(1, server.requestCount) // one /chart fetch, reused by every section
    }
}
