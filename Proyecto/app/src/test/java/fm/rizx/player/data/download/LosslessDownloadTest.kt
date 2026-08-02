package fm.rizx.player.data.download

import fm.rizx.player.data.lossless.FlacFixtures
import fm.rizx.player.domain.model.AudioProvenance
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Downloading a verified FLAC means writing **the bytes that arrived**, and refusing to index anything
 * else as one.
 *
 * The header was checked from 64 KiB before playback; by the time a whole file is on disk that proves
 * less than it looks like, because the interesting failures — a truncated body, a swapped file, a host
 * that started serving an error page halfway — all happen after the first block. So the finished file is
 * checked again, and a file that fails is deleted rather than left for the offline resolver to prefer
 * forever.
 */
class LosslessDownloadTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File

    private val client = OkHttpClient.Builder().build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("rizx-downloads").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun downloader() = TrackDownloader(client, dir, io = Dispatchers.Unconfined)

    @Test
    fun `a FLAC stream is saved as flac, byte for byte, with no conversion`() = runTest {
        val body = FlacFixtures.file(size = 200_000)
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        val done = downloader().download("deezer:1", flacStream())

        assertEquals("flac", done.container)
        assertEquals("audio/flac", done.mimeType)
        assertEquals(body.size.toLong(), done.sizeBytes)
        assertTrue(done.file.name.endsWith(".flac"))
        assertTrue("the bytes must be unchanged", done.file.readBytes().contentEquals(body))
    }

    @Test
    fun `a body that is not a FLAC is refused and leaves nothing behind`() = runTest {
        // The container was verified from the first 64 KiB before playback; this is the case where what
        // actually arrived is something else entirely.
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(50_000) { 0x41 })))

        val error = runCatching { downloader().download("deezer:1", flacStream()) }.exceptionOrNull()

        assertTrue(error?.message?.contains("not a FLAC") == true)
        assertEquals("no orphan file, no orphan .part", 0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `a truncated body is refused before it can be indexed`() = runTest {
        // OkHttp can report a body cut mid-flight as a clean EOF. Without the length check the file
        // plays for forty seconds and then dies — permanently, since the resolver prefers it.
        // Chunked, so there is no Content-Length header and the expected size comes from the Stream —
        // which is where it comes from in production too, read off the inspector's Content-Range.
        val body = FlacFixtures.file(size = 100_000)
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(body.copyOf(40_000)), 4096))

        val error = runCatching {
            downloader().download("deezer:1", flacStream().copy(contentLengthBytes = body.size.toLong()))
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("incomplete") == true)
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `a published checksum that matches lets the file through`() = runTest {
        val body = FlacFixtures.file(size = 120_000)
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        val done = downloader().download("deezer:1", flacStream(), expectedSha256 = sha256(body))

        assertTrue(done.file.isFile)
    }

    @Test
    fun `a published checksum that does not match deletes the file`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(FlacFixtures.file(size = 120_000))))

        val error = runCatching {
            downloader().download("deezer:1", flacStream(), expectedSha256 = "0".repeat(64))
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("checksum") == true)
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `no checksum published is not a failure — the reference index publishes none`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(FlacFixtures.file(size = 120_000))))

        assertTrue(downloader().download("deezer:1", flacStream(), expectedSha256 = null).file.isFile)
    }

    @Test
    fun `the magic-byte check applies only to files claiming to be FLAC`() = runTest {
        // An M4A download must not be held to a FLAC's rules — it would fail every single time.
        val m4a = ByteArray(20_000) { 0x00 }
        server.enqueue(MockResponse().setBody(Buffer().write(m4a)))

        val done = downloader().download(
            "deezer:1",
            Stream(
                url = server.url("/song.m4a").toString(),
                protocol = StreamProtocol.HTTPS,
                mimeType = "audio/mp4",
                container = "m4a",
                source = ProviderRef("youtube", "v1"),
            ),
        )

        assertEquals("m4a", done.container)
        assertTrue(done.file.isFile)
    }

    @Test
    fun `a community FLAC is downloadable — it is neither HLS nor a preview provider`() {
        assertTrue(flacStream().isDownloadable())
    }

    @Test
    fun `an HLS stream is still refused, community or not`() {
        assertFalse(flacStream().copy(protocol = StreamProtocol.HLS).isDownloadable())
    }

    private fun flacStream() = Stream(
        url = server.url("/song.flac").toString(),
        protocol = StreamProtocol.HTTPS,
        mimeType = "audio/flac",
        codec = "FLAC",
        container = "flac",
        sampleRateHz = 44_100,
        bitsPerSample = 16,
        channels = 2,
        provenance = AudioProvenance.COMMUNITY_UNVERIFIED,
        source = ProviderRef("community-lossless", "deezer:1"),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
