package fm.rizx.player.data.search

import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.YoutubePlaylistData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException

/** The Search "Playlists" orchestrator: Deezer + YouTube in parallel, degrading independently. */
class PlaylistSourcesSearchTest {

    private lateinit var server: MockWebServer
    private lateinit var deezer: DeezerApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        deezer = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DeezerApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private val ytServiceId = ServiceList.YouTube.serviceId

    private fun ytPlaylist(listId: String, name: String, count: Long = 40): PlaylistInfoItem =
        PlaylistInfoItem(ytServiceId, "https://www.youtube.com/playlist?list=$listId", name).apply { streamCount = count }

    private class FakeYoutube(
        private val items: List<PlaylistInfoItem>,
        private val throws: Throwable? = null,
    ) : YoutubeExtractorClient {
        override fun searchSongs(query: String, limit: Int): List<StreamInfoItem> = error("not used")
        override fun searchVideos(query: String, limit: Int): List<StreamInfoItem> = error("not used")
        override fun searchPlaylists(query: String, limit: Int): List<PlaylistInfoItem> {
            throws?.let { throw it }
            return items.take(limit)
        }
        override fun streamInfo(videoUrl: String): StreamInfo = error("not used")
        override fun playlist(playlistUrl: String): YoutubePlaylistData = error("not used")
        override fun mix(videoId: String, limit: Int): List<StreamInfoItem> = error("not used")
    }

    private fun search(yt: FakeYoutube) = DefaultPlaylistSourcesSearch(deezer, yt, io = Dispatchers.Unconfined)

    @Test
    fun `merges Deezer then YouTube playlists`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":1,"title":"Chill Vibes","nb_tracks":50,"picture_medium":"http://img/p.jpg"}]}"""))

        val results = search(FakeYoutube(listOf(ytPlaylist("PL1234567890abcdef", "YT Mix", count = 40)))).search("chill")

        // Deezer first, then YouTube — the screen groups them under their own headings by source.
        assertEquals(2, results.playlists.size)
        val dz = results.playlists[0]
        assertEquals("deezer", dz.source.provider)
        assertEquals("Chill Vibes", dz.name)
        assertEquals(50, dz.trackCount)
        val yt = results.playlists[1]
        assertEquals("youtube", yt.source.provider)
        assertEquals("PL1234567890abcdef", yt.id)
        assertEquals("playlist:PL1234567890abcdef", yt.source.id) // namespaced, so opening rebuilds the exact URL
        assertEquals(40, yt.trackCount)
    }

    @Test
    fun `a failing YouTube source degrades independently`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":9,"title":"Deezer Only","nb_tracks":10}]}"""))

        val results = search(FakeYoutube(emptyList(), throws = IOException("youtube down"))).search("chill")

        assertEquals(1, results.playlists.size)
        assertEquals("deezer", results.playlists.single().source.provider)
    }

    @Test
    fun `a failing Deezer source degrades independently`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val results = search(FakeYoutube(listOf(ytPlaylist("PL0987654321zyxwvut", "YT Only")))).search("chill")

        assertEquals(1, results.playlists.size)
        assertEquals("youtube", results.playlists.single().source.provider)
    }

    @Test
    fun `a blank query touches neither source`() = runBlocking {
        val results = search(FakeYoutube(emptyList(), throws = IllegalStateException("must not be called"))).search("   ")

        assertTrue(results.playlists.isEmpty())
        assertEquals(0, server.requestCount)
    }
}
