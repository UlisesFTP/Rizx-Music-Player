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

    /** Routes by path — the featured fetch is chart + concurrent tracklists, so enqueue order can't. */
    private fun serveByPath() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = when {
                    path.startsWith("/chart") -> chartBody
                    path.startsWith("/radio/lists") ->
                        // A dupe title and a trailing space, both straight from the live endpoint.
                        """{"data":[
                            {"id":37151,"title":"Hits","picture_medium":"https://r/1.jpg"},
                            {"id":42042,"title":"Hits"},
                            {"id":38305,"title":"80's "},
                            {"id":37121,"title":"Chill Out"}]}"""
                    path.startsWith("/radio/31061/tracks") ->
                        """{"data":[
                            {"id":11,"title":"Abracadabra","duration":223,"artist":{"id":5,"name":"Lady Gaga"}},
                            {"id":12,"title":"Friend Of Mine","duration":190,"artist":{"id":6,"name":"Rihanna"}}]}"""
                    path.startsWith("/playlist/900/tracks") ->
                        """{"data":[
                            {"id":21,"title":"NADIE","duration":180,"artist":{"id":7,"name":"Tito Double P"}},
                            {"id":22,"title":"Dos días","duration":200,"artist":{"id":7,"name":"Tito Double P"}}],
                            "total":50}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
    }

    @Test
    fun `mood stations are trimmed and deduped by title`() = runBlocking {
        serveByPath()

        val stations = provider().moodStations(10)

        assertEquals(listOf("Hits", "80's", "Chill Out"), stations.map { it.title })
        assertEquals("37151", stations.first().id) // the first spelling of a duped title wins
    }

    @Test
    fun `station tracks map like any Deezer track rows`() = runBlocking {
        serveByPath()

        val tracks = provider().stationTracks("31061", 30)

        assertEquals(listOf("Abracadabra", "Friend Of Mine"), tracks.map { it.title })
        assertEquals("Lady Gaga", tracks.first().artists.single().name)
    }

    @Test
    fun `featured playlists carry a track peek and keep the playlist's identity`() = runBlocking {
        serveByPath()

        val cards = provider().featuredPlaylists(2)

        val card = cards.single() // the chart offers one playlist
        assertEquals("Top France", card.playlist.name)
        assertEquals("playlist:900", card.playlist.source.id)
        assertEquals(listOf("NADIE", "Dos días"), card.preview.map { it.title })
    }

    @Test
    fun `a playlist whose peek cannot be fetched is dropped rather than shown hollow`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(chartBody))
        server.enqueue(MockResponse().setResponseCode(500)) // the tracklist call fails

        assertEquals(0, provider().featuredPlaylists(2).size)
    }
}
