package fm.rizx.player.data.recognition

import fm.rizx.player.domain.recognition.RecognitionAudio
import fm.rizx.player.domain.recognition.RecognitionError
import fm.rizx.player.domain.recognition.RecognitionOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The policy layer: how often the service is asked, when asking again is worth it, and when the answer
 * is already known. All of it is measured in *requests made*, since that is the only part a third
 * party can feel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShazamRecognitionProviderTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private var clock = 1_000L

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider() = ShazamRecognitionProvider(
        client = ShazamRecognitionClient(
            okHttpClient = OkHttpClient(),
            json = json,
            baseUrl = server.url("/").toString().trimEnd('/'),
        ),
        signatures = { "data:audio/vnd.shazam.sig;base64,AAAA" },
        resampler = Pcm16Resampler(),
        io = UnconfinedTestDispatcher(),
        now = { clock },
        jitter = { 0.0 },
    )

    /** Half a second of 16 kHz silence — the resampler passes it through untouched. */
    private fun audio() = RecognitionAudio(
        pcm16LittleEndian = ByteArray(16_000),
        sampleRateHz = 16_000,
        channelCount = 1,
        durationMs = 500,
    )

    @Test
    fun `a match comes back and asks once`() = runTest {
        server.enqueue(MockResponse().setBody(MATCH))

        val outcome = provider().recognize(audio())

        assertEquals("Song", (outcome as RecognitionOutcome.Matched).match.title)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a rate limit is retried, but only three times in total`() = runTest {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(429)) }

        val outcome = provider().recognize(audio())

        assertEquals(RecognitionOutcome.Failed(RecognitionError.RATE_LIMITED), outcome)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `an outage that clears is not reported as a failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(MATCH))

        val outcome = provider().recognize(audio())

        assertEquals("Song", (outcome as RecognitionOutcome.Matched).match.title)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a refusal is never retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setBody(MATCH))

        val outcome = provider().recognize(audio())

        assertEquals(RecognitionOutcome.Failed(RecognitionError.INVALID_RESPONSE), outcome)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the same fingerprint is not asked twice`() = runTest {
        server.enqueue(MockResponse().setBody(MATCH))
        val provider = provider()

        val first = provider.recognize(audio())
        val second = provider.recognize(audio())

        assertEquals(first, second)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a no-match is remembered too`() = runTest {
        server.enqueue(MockResponse().setBody("""{"matches":[]}"""))
        val provider = provider()

        assertEquals(RecognitionOutcome.NoMatch, provider.recognize(audio()))
        assertEquals(RecognitionOutcome.NoMatch, provider.recognize(audio()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a failure is never remembered — the network comes back`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setBody(MATCH))
        val provider = provider()

        assertEquals(RecognitionOutcome.Failed(RecognitionError.INVALID_RESPONSE), provider.recognize(audio()))
        assertEquals("Song", (provider.recognize(audio()) as RecognitionOutcome.Matched).match.title)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a stale memo expires`() = runTest {
        server.enqueue(MockResponse().setBody(MATCH))
        server.enqueue(MockResponse().setBody(MATCH))
        val provider = provider()

        provider.recognize(audio())
        clock += 6 * 60 * 1_000L // past the five-minute window
        provider.recognize(audio())

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a broken fingerprint never reaches the network`() = runTest {
        val provider = ShazamRecognitionProvider(
            client = ShazamRecognitionClient(OkHttpClient(), json, server.url("/").toString().trimEnd('/')),
            signatures = { error("boom") },
            resampler = Pcm16Resampler(),
            io = UnconfinedTestDispatcher(),
            now = { clock },
        )

        assertEquals(
            RecognitionOutcome.Failed(RecognitionError.SIGNATURE_FAILED),
            provider.recognize(audio()),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `unusable audio never reaches the network`() = runTest {
        val outcome = provider().recognize(
            RecognitionAudio(ByteArray(0), sampleRateHz = 44_100, channelCount = 2, durationMs = 0),
        )

        assertEquals(RecognitionOutcome.Failed(RecognitionError.RESAMPLING_FAILED), outcome)
        assertEquals(0, server.requestCount)
    }

    private companion object {
        const val MATCH = """{"track":{"key":"1","title":"Song","subtitle":"Artist"}}"""
    }
}
