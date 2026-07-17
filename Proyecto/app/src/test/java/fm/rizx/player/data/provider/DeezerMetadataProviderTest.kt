package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.deezer.DeezerIds
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchParams
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

class DeezerMetadataProviderTest {

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

    private fun provider() = DeezerMetadataProvider(api, io = Dispatchers.Unconfined)

    @Test
    fun `search maps grouped results`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":1,"title":"One More Time","duration":320,"artist":{"id":27,"name":"Daft Punk"}}]}"""))

        val results = provider().search(SearchParams("daft punk"))

        assertEquals("One More Time", results.tracks.single().title)
    }

    @Test
    fun `search with the ARTISTS type hits the dedicated artist index`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":27,"name":"Daft Punk","picture_medium":"http://img/a.jpg"}]}"""))

        val results = provider().search(SearchParams("daft punk", types = listOf(fm.rizx.player.domain.model.SearchCategory.ARTISTS)))

        assertEquals("Daft Punk", results.artists.single().name)
        assertTrue(results.tracks.isEmpty() && results.albums.isEmpty())
        assertTrue(server.takeRequest().path!!.contains("/search/artist"))
    }

    @Test
    fun `search with the ALBUMS type hits the dedicated album index and keeps the artist`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":302127,"title":"Discovery","cover_medium":"http://img/c.jpg","artist":{"id":27,"name":"Daft Punk"}}]}"""))

        val results = provider().search(SearchParams("discovery", types = listOf(fm.rizx.player.domain.model.SearchCategory.ALBUMS)))

        val album = results.albums.single()
        assertEquals("Discovery", album.title)
        assertEquals("Daft Punk", album.artists.single().name) // the new search/album artist field
        assertTrue(results.tracks.isEmpty() && results.artists.isEmpty())
        assertTrue(server.takeRequest().path!!.contains("/search/album"))
    }

    @Test
    fun `albumDetail returns a full album with tracks`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":302127,"title":"Discovery","nb_tracks":2,"tracks":{"data":[{"id":10,"title":"One More Time","duration":320},{"id":11,"title":"Aerodynamic","duration":212}]}}"""))

        val album = provider().albumDetail(DeezerIds.album(302127))!!

        assertEquals("Discovery", album.title)
        assertEquals(listOf("One More Time", "Aerodynamic"), album.tracks.map { it.title })
    }

    @Test
    fun `artistDetail fans out header, top tracks and albums`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":27,"name":"Daft Punk","nb_fan":9600000}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":10,"title":"One More Time","duration":320}]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":302127,"title":"Discovery"}]}"""))

        val artist = provider().artistDetail(DeezerIds.artist(27))!!

        assertEquals("Daft Punk", artist.name)
        assertEquals(9_600_000L, artist.followers)
        assertEquals(listOf("One More Time"), artist.topTracks.map { it.title })
        assertEquals(listOf("Discovery"), artist.albums.map { it.title })
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `radioTracks fetches the artist radio for a deezer seed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":50,"title":"Lose Yourself to Dance","duration":354,"artist":{"id":27,"name":"Daft Punk"}},{"id":51,"title":"Robot Rock","duration":288,"artist":{"id":27,"name":"Daft Punk"}}]}"""))
        val seed = Track(title = "One More Time", artists = listOf(ArtistCredit(name = "Daft Punk", source = DeezerIds.artist(27))), source = DeezerIds.track(10))

        val radio = provider().radioTracks(seed)

        assertEquals(listOf("Lose Yourself to Dance", "Robot Rock"), radio.map { it.title })
        assertTrue(server.takeRequest().path!!.contains("/artist/27/radio"))
    }

    @Test
    fun `radioTracks falls back to a deezer search when the seed has no deezer artist`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":60,"title":"Blinding Lights","duration":200,"artist":{"id":4050205,"name":"The Weeknd"}}]}"""))
        val seed = Track(title = "Save Your Tears", artists = listOf(ArtistCredit(name = "The Weeknd", source = ProviderRef("itunes", "artist:1"))), source = ProviderRef("itunes", "99"))

        val radio = provider().radioTracks(seed)

        assertEquals(listOf("Blinding Lights"), radio.map { it.title })
        assertTrue(server.takeRequest().path!!.contains("/search"))
    }

    @Test
    fun `playlistTracks maps a deezer playlist's tracks`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":123,"title":"En mode 60","tracks":{"data":[{"id":70,"title":"Hey Jude","duration":431},{"id":71,"title":"Let It Be","duration":243}]}}"""))

        val tracks = provider().playlistTracks(DeezerIds.playlist(123))

        assertEquals(listOf("Hey Jude", "Let It Be"), tracks.map { it.title })
        assertTrue(server.takeRequest().path!!.contains("/playlist/123"))
    }

    @Test
    fun `a server error surfaces as a typed AppError`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val error = assertThrows(AppError::class.java) { runBlocking { provider().search(SearchParams("x")) } }
        assertTrue(error is AppError.ProviderFailure)
    }

    @Test
    fun `empty query never touches the network`() = runBlocking {
        assertTrue(provider().search(SearchParams("  ")).isEmpty)
        assertEquals(0, server.requestCount)
    }
}
