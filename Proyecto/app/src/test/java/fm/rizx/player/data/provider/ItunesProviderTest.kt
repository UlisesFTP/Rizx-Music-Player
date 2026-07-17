package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.itunes.ItunesApi
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

/**
 * Provider error-handling and mapping against a **fake HTTP server** (MockWebServer). Verifies the
 * happy path plus that every failure mode surfaces as a typed [AppError] rather than crashing.
 */
class ItunesProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ItunesApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ItunesApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun metadata() = ItunesMetadataProvider(api, io = Dispatchers.Unconfined)
    private fun streaming() = ItunesStreamingProvider(api, io = Dispatchers.Unconfined)

    private val songBody = """
        {"resultCount":1,"results":[{"wrapperType":"track","kind":"song","trackId":111,
        "artistId":9,"collectionId":77,"trackName":"Velvet Hours","artistName":"Aurora Lane",
        "collectionName":"Nightfall","previewUrl":"https://audio.example.com/velvet.m4a",
        "artworkUrl100":"https://art/100x100bb.jpg","trackTimeMillis":180000}]}
    """.trimIndent()

    private fun track() = Track(title = "Velvet Hours", source = ProviderRef("itunes", "111"))

    @Test
    fun `search maps a 200 response to grouped results`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(songBody))

        val results = metadata().search(fm.rizx.player.domain.model.SearchParams("aurora"))

        assertEquals(1, results.tracks.size)
        assertEquals("Velvet Hours", results.tracks.first().title)
    }

    @Test
    fun `search wraps a 500 in a typed AppError`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val error = assertThrows(AppError::class.java) {
            runBlocking { metadata().search(fm.rizx.player.domain.model.SearchParams("aurora")) }
        }
        assertTrue(error is AppError.ProviderFailure)
    }

    @Test
    fun `search wraps malformed JSON in a typed AppError`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("this is not json"))

        val error = assertThrows(AppError::class.java) {
            runBlocking { metadata().search(fm.rizx.player.domain.model.SearchParams("aurora")) }
        }
        assertTrue(error is AppError)
    }

    @Test
    fun `empty query never touches the network`() = runBlocking {
        val results = metadata().search(fm.rizx.player.domain.model.SearchParams("   "))

        assertTrue(results.isEmpty)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `searchForTrack maps candidates from the search endpoint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(songBody))

        val candidates = streaming().searchForTrack(track())

        assertEquals(1, candidates.size)
        assertEquals("111", candidates.first().id)
    }

    @Test
    fun `getStreamUrl resolves the preview url via lookup`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(songBody))

        val stream = streaming().getStreamUrl(StreamCandidate(id = "111", title = "Velvet Hours", source = ProviderRef("itunes-streaming", "111")))

        assertEquals("https://audio.example.com/velvet.m4a", stream.url)
    }

    @Test
    fun `getStreamUrl fails typed when the lookup has no preview`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"resultCount":1,"results":[{"trackId":111,"trackName":"No Preview"}]}"""))

        val error = assertThrows(AppError::class.java) {
            runBlocking { streaming().getStreamUrl(StreamCandidate(id = "111", title = "x", source = ProviderRef("itunes-streaming", "111"))) }
        }
        assertTrue(error is AppError.ProviderFailure)
    }
}
