package fm.rizx.player.data.provider

import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.domain.model.ArtworkTargetPx
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.domain.model.thumbnailUrl
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
                            {"id":37151,"title":"Hits","picture_xl":"https://r/xl.jpg","picture_big":"https://r/big.jpg","picture_medium":"https://r/1.jpg"},
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

    /**
     * A station tile is a photo now, and every tile in the app picks its own rung — so a station has to
     * carry the whole set rather than the one 250px URL the mapper used to keep.
     */
    @Test
    fun `a station carries its full cover set, so data saver can pick the cheap rung`() = runBlocking {
        serveByPath()

        val artwork = provider().moodStations(10).first().artwork

        // The cheap rung is the 250 (data saver), the tile rung the 500 — both real choices now,
        // where before there was a single 250px URL and nothing to choose between.
        assertEquals("https://r/1.jpg", artwork.thumbnailUrl())
        assertEquals("https://r/big.jpg", artwork.coverUrl(ArtworkTargetPx.COVER))
    }

    /** The whole published list in one call — Home previews it and its "See all" needs the rest. */
    @Test
    fun `the station list is fetched deep enough to be the whole catalogue`() = runBlocking {
        serveByPath()

        provider().moodStations(10)

        assertEquals("/radio/lists?limit=100", server.takeRequest().path)
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

    // ---- Genre browse (`/chart/{genreId}`) ----

    /**
     * A metal chart shaped like the live one: genre-correct tracks, albums and playlists, and an
     * `artists` section carrying whatever is globally charting — which is exactly what Deezer returns
     * for **every** genre id, including ids that do not exist.
     */
    private val metalChartBody = """
        {"tracks":{"data":[
            {"id":1,"title":"Master Of Puppets","duration":515,"artist":{"id":119,"name":"Metallica","picture_xl":"https://p/m.jpg"}},
            {"id":2,"title":"Toxicity","duration":218,"artist":{"id":220,"name":"System of a Down"}},
            {"id":3,"title":"One","duration":447,"artist":{"id":119,"name":"Metallica"}}]},
         "albums":{"data":[{"id":77,"title":"Megadeth","cover_xl":"https://c/xl.jpg","artist":{"id":330,"name":"Megadeth"}}]},
         "artists":{"data":[{"id":10583405,"name":"Bad Bunny","picture_xl":"https://p/bb.jpg"}]},
         "playlists":{"data":[{"id":901,"title":"Metal Essentials","picture_xl":"https://pl/xl.jpg","nb_tracks":80}]}}
    """.trimIndent()

    @Test
    fun `a genre feed carries the genre's own tracks, albums and playlists`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(metalChartBody))

        val feed = provider().genreFeed("464", 50)

        assertEquals(listOf("Master Of Puppets", "Toxicity", "One"), feed.tracks.map { it.title })
        assertEquals(listOf("Megadeth"), feed.albums.map { it.title })
        assertEquals(listOf("Metal Essentials"), feed.playlists.map { it.name })
        assertEquals("/chart/464?limit=50", server.takeRequest().path)
    }

    /**
     * The regression that matters: Deezer's `artists` section is **not** genre-filtered. Shipping it
     * would put Bad Bunny at the top of Metal, so the artists are derived from the rows that *are*
     * filtered — deduped, and in the order they charted.
     */
    @Test
    fun `genre artists come from the genre's tracks and albums, never from the artists section`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(metalChartBody))

        val artists = provider().genreFeed("464", 50).artists

        assertEquals(listOf("Metallica", "System of a Down", "Megadeth"), artists.map { it.name })
        assertEquals(false, artists.any { it.name == "Bad Bunny" })
        assertEquals("artist:119", artists.first().source.id)
    }

    @Test
    fun `a genre id that is not a number never reaches the network`() = runBlocking {
        val feed = provider().genreFeed("../chart", 50)

        assertEquals(true, feed.isEmpty)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `each genre is fetched once and does not evict the global chart`() = runBlocking {
        serveByPath()
        val p = provider()

        p.genreFeed("464", 50)
        p.genreFeed("464", 50)
        p.topTracks(10)

        assertEquals(2, server.requestCount) // one per genre, plus the global chart
    }
}
