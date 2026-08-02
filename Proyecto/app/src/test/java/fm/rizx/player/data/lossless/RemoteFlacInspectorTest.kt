package fm.rizx.player.data.lossless

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The inspector's job is to spend 64 KiB deciding a 27 MB question, and to refuse to be steered
 * anywhere it shouldn't go while doing it.
 *
 * The server here speaks plain HTTP from loopback — which production refuses outright — so these run
 * against a guard built with the documented test seam. The strict rules themselves are covered by
 * [LosslessUrlGuardTest], and the "strict guard refuses http" case is asserted here too so the seam
 * can't quietly become the default.
 */
class RemoteFlacInspectorTest {

    private lateinit var server: MockWebServer

    private val client = OkHttpClient.Builder().build()

    /** Loopback + plain HTTP allowed, every other rule intact. */
    private fun inspector() = RemoteFlacInspector(
        client = client,
        io = Dispatchers.Unconfined,
        guard = LosslessUrlGuard(allowLoopbackOverHttp = true),
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * The server's URL, forced onto the loopback literal.
     *
     * `MockWebServer.url()` uses the loopback address's *canonical hostname*, which on a machine with
     * Docker Desktop installed is `kubernetes.docker.internal` — a name that resolves to 127.0.0.1 but
     * reads like a private-network host, because it is one. Left alone, this test would pass or fail
     * depending on what else is installed on the machine running it.
     */
    private fun url(path: String): String =
        server.url(path).newBuilder().host("127.0.0.1").build().toString()

    @Test
    fun `reads the header from a 206 and takes the total size from Content-Range`() = runTest {
        // The trap: on a 206 the Content-Length is the *slice* (65536). Taking that as the file size
        // would report every song at a couple of hundred kbps.
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-65535/27110494")
                .setBody(Buffer().write(FlacFixtures.head(sampleRate = 48_000, totalSamples = 11_392_533L))),
        )

        val info = inspector().inspect(url("/song.flac"))!!

        assertEquals(48_000, info.sampleRateHz)
        assertEquals(27_110_494L, info.contentLength)
    }

    @Test
    fun `sends a ranged request for the first 64 KiB`() = runTest {
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(FlacFixtures.head())))

        inspector().inspect(url("/song.flac"))

        assertEquals("bytes=0-65535", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `accepts a 200 from a server that ignores Range, and uses its Content-Length`() = runTest {
        val body = FlacFixtures.file(size = 4_000_000)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))

        val info = inspector().inspect(url("/song.flac"))!!

        assertEquals(4_000_000L, info.contentLength)
    }

    @Test
    fun `computes the effective bitrate from real bytes over real seconds`() = runTest {
        // 3 minutes of audio in 27,110,494 bytes ≈ 1205 kbps. Never the 1411 that gets quoted for
        // "CD quality" — that is uncompressed PCM and says nothing about a FLAC.
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-65535/27110494")
                .setBody(Buffer().write(FlacFixtures.head(totalSamples = 44_100L * 180))),
        )

        val info = inspector().inspect(url("/song.flac"))!!

        assertEquals(1205, info.effectiveBitrateKbps)
    }

    @Test
    fun `caps the read when a server streams the whole file instead of a slice`() = runTest {
        // Two megabytes offered, 64 KiB taken. Without the cap this is the request that quietly
        // downloads a 27 MB file to look at 34 bytes.
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(FlacFixtures.file(2_000_000))))

        assertNotNull(inspector().inspect(url("/song.flac")))
    }

    @Test
    fun `rejects a URL that ends in flac but does not contain a FLAC`() = runTest {
        // The whole reason the header is read at all.
        val mp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x44) + ByteArray(5000)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(mp3)))

        assertNull(inspector().inspect(url("/not-really.flac")))
    }

    @Test
    fun `accepts a real FLAC served from a URL with no extension at all`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(FlacFixtures.head())))

        assertNotNull(inspector().inspect(url("/download?id=42")))
    }

    @Test
    fun `follows a redirect and inspects the target`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/real.flac"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(FlacFixtures.head())))

        assertNotNull(inspector().inspect(url("/start.flac")))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `refuses a redirect that points somewhere private`() = runTest {
        // The reason redirects are followed by hand: a perfectly public host can answer with a
        // Location pointing at the device's own network, and OkHttp's automatic following would
        // never consult the guard again.
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", "https://192.168.1.1/admin.flac"),
        )

        assertNull(inspector().inspect(url("/start.flac")))
    }

    @Test
    fun `gives up after too many redirects`() = runTest {
        repeat(6) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next$it.flac")) }

        assertNull(inspector().inspect(url("/start.flac")))
        assertTrue("should stop well before the queue empties", server.requestCount <= 4)
    }

    @Test
    fun `a 404 is a miss, not a crash`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        assertNull(inspector().inspect(url("/gone.flac")))
    }

    @Test
    fun `the strict guard refuses plain HTTP without making a request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(FlacFixtures.head())))

        val strict = RemoteFlacInspector(client = client, io = Dispatchers.Unconfined)

        assertNull(strict.inspect(url("/song.flac")))
        assertEquals("nothing should have been sent", 0, server.requestCount)
    }
}
