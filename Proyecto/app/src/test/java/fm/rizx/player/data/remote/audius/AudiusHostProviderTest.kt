package fm.rizx.player.data.remote.audius

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

class AudiusHostProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AudiusApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AudiusApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun hostProvider() =
        AudiusHostProvider(api, discoveryUrl = server.url("/").toString(), fallbackHost = "https://fallback.audius.co")

    @Test
    fun `picks the first discovered host and caches it`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":["https://dn1.audius.co/","https://dn2.audius.co"]}"""))
        val hosts = hostProvider()

        assertEquals("https://dn1.audius.co", hosts.host()) // trailing slash trimmed
        assertEquals("https://dn1.audius.co", hosts.host()) // cached — no second request
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `falls back when discovery fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals("https://fallback.audius.co", hostProvider().host())
    }

    @Test
    fun `markBad forces re-discovery`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":["https://dn1.audius.co"]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":["https://dn2.audius.co"]}"""))
        val hosts = hostProvider()

        assertEquals("https://dn1.audius.co", hosts.host())
        hosts.markBad("https://dn1.audius.co")
        assertEquals("https://dn2.audius.co", hosts.host())
    }
}
