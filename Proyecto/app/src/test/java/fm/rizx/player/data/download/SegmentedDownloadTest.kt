package fm.rizx.player.data.download

import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The segmented downloader against a server that actually honours ranges.
 *
 * The property that matters is written in the first test's name: however the chunks were fetched,
 * retried, or interleaved, **the file on disk is byte-for-byte the original**. Everything else — how
 * many requests, which fallbacks — is mechanism in service of that.
 */
class SegmentedDownloadTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File

    private val client = OkHttpClient.Builder().build()

    /** Deterministic pseudo-random body, so corruption anywhere shows up as inequality. */
    private val body = Random(7).nextBytes(5 * 1024 * 1024 + 12_345)

    @Before
    fun setUp() {
        server = MockWebServer()
        dir = Files.createTempDirectory("rizx-segmented").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    /** A dispatcher that serves [body] honouring `Range`, like a real CDN. */
    private fun rangeDispatcher(
        failFirstRequestsForChunksAfterProbe: Int = 0,
    ) = object : Dispatcher() {
        val requests = AtomicInteger(0)
        val failures = AtomicInteger(failFirstRequestsForChunksAfterProbe)

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.incrementAndGet()
            val range = request.getHeader("Range") ?: return MockResponse().setBody(Buffer().write(body))
            val match = Regex("bytes=(\\d+)-(\\d+)?").find(range) ?: return MockResponse().setResponseCode(416)
            val start = match.groupValues[1].toInt()
            // A mid-file chunk that dies on its first attempt exercises the per-chunk retry. The body is
            // the *true* slice: whatever lands before the cut is real data at real offsets, exactly as a
            // dropped connection leaves things — the retry resumes after it, it doesn't repaint it.
            if (start > 0 && failures.getAndDecrement() > 0) {
                val slice = body.copyOfRange(start, minOf(start + 130_000, body.size))
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-${start + slice.size - 1}/${body.size}")
                    .setBody(Buffer().write(slice))
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            }
            val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: (body.size - 1)
            val slice = body.copyOfRange(start, minOf(end + 1, body.size))
            return MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-${start + slice.size - 1}/${body.size}")
                .setHeader("Content-Type", "audio/mp4")
                .setBody(Buffer().write(slice))
        }
    }

    private fun downloader(workers: Int = 3) =
        TrackDownloader(client, dir, io = Dispatchers.Unconfined, maxWorkers = { workers })

    private fun stream() = Stream(
        url = server.url("/song.m4a").toString(),
        protocol = StreamProtocol.HTTPS,
        mimeType = "audio/mp4",
        container = "m4a",
        source = ProviderRef("youtube", "v1"),
    )

    @Test
    fun `a ranged download reassembles the exact original bytes`() = runTest {
        server.dispatcher = rangeDispatcher()
        server.start()

        val done = downloader().download("yt:v1", stream())

        assertEquals(body.size.toLong(), done.sizeBytes)
        assertTrue("chunked reassembly must be byte-identical", done.file.readBytes().contentEquals(body))
    }

    @Test
    fun `the probe carries the first chunk, so a 5 MB file costs exactly total-over-chunk requests`() = runTest {
        val dispatcher = rangeDispatcher()
        server.dispatcher = dispatcher
        server.start()

        downloader().download("yt:v1", stream())

        // ceil(5.01 MiB / 2 MiB) = 3 — the probe IS the first one, not an extra HEAD on top.
        assertEquals(3, dispatcher.requests.get())
    }

    @Test
    fun `a chunk that dies mid-body is retried and the file still comes out whole`() = runTest {
        server.dispatcher = rangeDispatcher(failFirstRequestsForChunksAfterProbe = 2)
        server.start()

        val done = downloader().download("yt:v1", stream())

        assertTrue(done.file.readBytes().contentEquals(body))
    }

    @Test
    fun `a server without ranges falls back to the classic single stream`() = runTest {
        // MockWebServer's default behaviour: ignore Range, answer 200 with the whole body.
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mp4").setBody(Buffer().write(body)))
        server.start()

        val done = downloader().download("yt:v1", stream())

        assertEquals(1, server.requestCount)
        assertTrue(done.file.readBytes().contentEquals(body))
    }

    @Test
    fun `a 206 that will not state the total is re-fetched whole rather than saved truncated`() = runTest {
        // `Content-Range: bytes 0-x/*` — the pathological server. Saving the probe body would pass the
        // length check (its Content-Length equals the chunk) while truncating the song at 2 MiB.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                    ?: return MockResponse().setHeader("Content-Type", "audio/mp4").setBody(Buffer().write(body))
                val end = Regex("bytes=0-(\\d+)").find(range)!!.groupValues[1].toInt()
                val slice = body.copyOfRange(0, end + 1)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-$end/*")
                    .setHeader("Content-Type", "audio/mp4")
                    .setBody(Buffer().write(slice))
            }
        }
        server.start()

        val done = downloader().download("yt:v1", stream())

        assertEquals(body.size.toLong(), done.sizeBytes)
        assertTrue(done.file.readBytes().contentEquals(body))
    }

    @Test
    fun `one worker still downloads everything — the bad-signal degraded mode`() = runTest {
        server.dispatcher = rangeDispatcher()
        server.start()

        val done = downloader(workers = 1).download("yt:v1", stream())

        assertTrue(done.file.readBytes().contentEquals(body))
    }

    @Test
    fun `progress is reported and reaches 100`() = runTest {
        server.dispatcher = rangeDispatcher()
        server.start()
        val seen = mutableListOf<Int>()

        downloader().download("yt:v1", stream()) { synchronized(seen) { seen += it } }

        assertTrue(seen.isNotEmpty())
        assertEquals(100, seen.max())
    }

    @Test
    fun `a small file that fits the probe chunk is served from that one response`() = runTest {
        val small = Random(3).nextBytes(300_000)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range") ?: return MockResponse().setBody(Buffer().write(small))
                Regex("bytes=(\\d+)-").find(range) ?: return MockResponse().setResponseCode(416)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-${small.size - 1}/${small.size}")
                    .setHeader("Content-Type", "audio/mp4")
                    .setBody(Buffer().write(small))
            }
        }
        server.start()

        val done = downloader().download("yt:v1", stream())

        assertEquals(1, server.requestCount)
        assertTrue(done.file.readBytes().contentEquals(small))
    }

    @Test
    fun `adopt writes through the same part-then-rename discipline`() = runTest {
        server.start()
        val payload = Random(9).nextBytes(80_000)

        val done = downloader().adopt("yt:v1", container = "m4a", mimeType = "audio/mp4") { part ->
            part.writeBytes(payload)
        }

        assertTrue(done.file.name.endsWith(".m4a"))
        assertTrue(done.file.readBytes().contentEquals(payload))
        assertEquals("no .part left behind", listOf(done.file.name), dir.listFiles()!!.map { it.name })
    }

    @Test
    fun `an adoption whose writer fails leaves nothing`() = runTest {
        server.start()

        val error = runCatching {
            downloader().adopt("yt:v1", container = "m4a", mimeType = null) { part ->
                part.writeBytes(ByteArray(10))
                error("copy died")
            }
        }.exceptionOrNull()

        assertTrue(error != null)
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }
}
