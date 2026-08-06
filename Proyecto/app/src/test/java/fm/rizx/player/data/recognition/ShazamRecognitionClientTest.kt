package fm.rizx.player.data.recognition

import fm.rizx.player.domain.recognition.RecognitionError
import fm.rizx.player.domain.recognition.RecognitionOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * The client against every answer the service can give. The payload below is a trimmed copy of a real
 * response, so the field names and nesting here are observed rather than imagined — including the two
 * that matter most: the artist arrives as a `subtitle` billing line, and the only exact identifiers
 * are `isrc` and the `applemusicplay` action's id.
 */
class ShazamRecognitionClientTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(locale: Locale = Locale("es", "MX")) = ShazamRecognitionClient(
        okHttpClient = OkHttpClient(),
        json = json,
        baseUrl = server.url("/").toString().trimEnd('/'),
        locale = { locale },
        timeZone = { "America/Mexico_City" },
        now = { 1_700_000_000_000 },
        newId = { "00000000-0000-0000-0000-000000000001" },
    )

    @Test
    fun `a full answer becomes a match`() = runBlocking {
        server.enqueue(MockResponse().setBody(FULL_RESPONSE))

        val outcome = client().tag(SIGNATURE, sampleMs = 12_000)

        val match = (outcome as RecognitionOutcome.Matched).match
        assertEquals("Get Lucky", match.title)
        assertEquals("Daft Punk, Pharrell Williams & Nile Rodgers", match.artist)
        assertEquals("Random Access Memories", match.album)
        assertEquals("Columbia", match.label)
        assertEquals("2013", match.releaseDate)
        assertEquals("USQX91300108", match.isrc)
        assertEquals("Pop", match.genre)
        assertEquals("617154366", match.appleTrackId)
        assertEquals("105842472", match.providerTrackId)
        assertEquals("https://www.shazam.com/track/105842472/get-lucky", match.externalUrl)
    }

    @Test
    fun `an answer stripped to the bone still becomes a match`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"track":{"title":" Song ","subtitle":"Artist"}}"""))

        val match = (client().tag(SIGNATURE, 12_000) as RecognitionOutcome.Matched).match
        assertEquals("Song", match.title) // trimmed
        assertEquals("Artist", match.artist)
        assertNull(match.isrc)
        assertNull(match.album)
        assertNull(match.appleTrackId)
    }

    @Test
    fun `nulls inside the arrays don't abort the parse`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"matches":[null],"track":{"title":"Song","subtitle":"Artist",
                   "sections":[null,{"type":"SONG","metadata":[null,{"title":"Album","text":"LP"}]}],
                   "hub":{"actions":[null,{"type":"applemusicplay","id":"42"}]}}}""",
            ),
        )

        val match = (client().tag(SIGNATURE, 12_000) as RecognitionOutcome.Matched).match
        assertEquals("LP", match.album)
        assertEquals("42", match.appleTrackId)
    }

    @Test
    fun `an empty match list is no match, not an error`() = runBlocking {
        // What the live service actually returns when it doesn't know the audio: 200, no track.
        server.enqueue(MockResponse().setBody("""{"matches":[],"tagid":"abc"}"""))
        assertEquals(RecognitionOutcome.NoMatch, client().tag(SIGNATURE, 12_000))
    }

    @Test
    fun `a track without a title is not a result`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"track":{"subtitle":"Artist"}}"""))
        assertEquals(RecognitionOutcome.NoMatch, client().tag(SIGNATURE, 12_000))
    }

    @Test
    fun `404 is no match`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(RecognitionOutcome.NoMatch, client().tag(SIGNATURE, 12_000))
    }

    @Test
    fun `429 is rate limited`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(
            RecognitionOutcome.Failed(RecognitionError.RATE_LIMITED),
            client().tag(SIGNATURE, 12_000),
        )
    }

    @Test
    fun `500 is a service outage`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(
            RecognitionOutcome.Failed(RecognitionError.SERVICE_UNAVAILABLE),
            client().tag(SIGNATURE, 12_000),
        )
    }

    @Test
    fun `a refusal is not retryable and not a match`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(
            RecognitionOutcome.Failed(RecognitionError.INVALID_RESPONSE),
            client().tag(SIGNATURE, 12_000),
        )
    }

    @Test
    fun `corrupt json is an invalid response`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"track":{"title":"""))
        assertEquals(
            RecognitionOutcome.Failed(RecognitionError.INVALID_RESPONSE),
            client().tag(SIGNATURE, 12_000),
        )
    }

    @Test
    fun `the request carries the expected path, parameters and headers`() = runBlocking {
        server.enqueue(MockResponse().setBody(FULL_RESPONSE))
        client().tag(SIGNATURE, 12_000)

        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals(
            listOf("discovery", "v5", "es", "MX", "android", "-", "tag"),
            url.pathSegments.take(7),
        )
        assertEquals("true", url.queryParameter("sync"))
        assertEquals("v3", url.queryParameter("shazamapiversion"))
        assertEquals("v3", url.queryParameter("video"))
        assertEquals("", url.queryParameter("connected"))
        assertEquals("es_MX", request.getHeader("Content-Language"))
        assertEquals(ShazamRecognitionClient.USER_AGENT, request.getHeader("User-Agent"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun `the body carries the fingerprint, the duration and a neutral location`() = runBlocking {
        server.enqueue(MockResponse().setBody(FULL_RESPONSE))
        client().tag(SIGNATURE, 12_000)

        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val signature = body["signature"]!!.jsonObject
        // The fingerprint's shape, not its bytes: asserting the whole string would turn every future
        // change to the generator into a failure here instead of in its own tests.
        assertTrue(signature["uri"]!!.jsonPrimitive.content.startsWith("data:audio/vnd.shazam.sig;base64,"))
        assertEquals("12000", signature["samplems"]!!.jsonPrimitive.content)
        assertEquals("America/Mexico_City", body["timezone"]!!.jsonPrimitive.content)

        // No location is ever sent, and no location permission is ever requested to obtain one.
        val geo: JsonObject = body["geolocation"]!!.jsonObject
        for (key in listOf("altitude", "latitude", "longitude")) {
            assertEquals(0.0, geo[key]!!.jsonPrimitive.content.toDouble(), 0.0)
        }
    }

    @Test
    fun `cancelling the caller cancels the request instead of waiting it out`() = runBlocking {
        // Headers arrive at once and the body then trickles — the shape that used to hang, because the
        // body was read after the coroutine had already been resumed and was therefore out of reach.
        server.enqueue(MockResponse().setBody(FULL_RESPONSE).throttleBody(16, 1, TimeUnit.SECONDS))

        val job = launch(Dispatchers.IO) { client().tag(SIGNATURE, 12_000) }
        server.takeRequest(5, TimeUnit.SECONDS)
        val elapsed = measureTimeMillis { job.cancelAndJoin() }

        assertTrue("cancellation took ${elapsed}ms", elapsed < 3_000)
    }

    private companion object {
        const val SIGNATURE = "data:audio/vnd.shazam.sig;base64,AAAA"

        /** Trimmed from a real response — same names, same nesting, same ordering quirks. */
        val FULL_RESPONSE = """
            {
              "matches": [{"id": "288472986", "offset": 140.12}],
              "tagid": "a43143c5-b155-49e7-b475-6d9da076633c",
              "track": {
                "key": "105842472",
                "title": "Get Lucky",
                "subtitle": "Daft Punk, Pharrell Williams & Nile Rodgers",
                "url": "https://www.shazam.com/track/105842472/get-lucky",
                "isrc": "USQX91300108",
                "images": {
                  "coverart": "https://example.invalid/400x400cc.jpg",
                  "coverarthq": "https://example.invalid/800x800cc.jpg"
                },
                "genres": {"primary": "Pop"},
                "hub": {
                  "actions": [
                    {"name": "apple", "type": "applemusicplay", "id": "617154366"},
                    {"name": "apple", "type": "uri", "uri": "https://example.invalid/preview.m4a"}
                  ]
                },
                "sections": [
                  {"type": "RELATED", "url": "https://example.invalid/related"},
                  {
                    "type": "SONG",
                    "metadata": [
                      {"title": "Album", "text": "Random Access Memories"},
                      {"title": "Label", "text": "Columbia"},
                      {"title": "Released", "text": "2013"}
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
    }
}
