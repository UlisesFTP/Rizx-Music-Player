package fm.rizx.player.data.update

import fm.rizx.player.domain.update.AppUpdate
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ApkDownloaderTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File

    @Before fun start() { server = MockWebServer(); server.start(); dir = File(folder.root, "updates") }
    @After fun stop() { server.shutdown() }

    private val bytes = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun update(sha256: String? = sha, size: Long = bytes.size.toLong(), name: String = "Rizx-1.1.0-release.apk") = AppUpdate(
        versionName = "1.1.0", tag = "v1.1.0", title = "Rizx 1.1.0", notes = "", publishedAtIso = null,
        pageUrl = "https://x/release", apkUrl = server.url("/dl/$name").toString(), apkName = name, apkBytes = size, sha256 = sha256,
    )

    private fun downloader() = ApkDownloader(OkHttpClient(), dir)

    @Test
    fun `streams the file, reports progress and verifies the checksum`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val progress = mutableListOf<Pair<Long, Long>>()
        val file = downloader().download(update()) { done, total -> progress += done to total }
        assertEquals(File(dir, "Rizx-1.1.0-release.apk"), file)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(bytes.size.toLong(), progress.last().first)
        assertEquals(bytes.size.toLong(), progress.last().second)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.first >= a.first })
        assertFalse(File(dir, "Rizx-1.1.0-release.apk.part").exists())
        assertEquals(file, downloader().existing(update()))
    }

    @Test
    fun `a checksum mismatch throws and leaves nothing behind`() {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val wrong = update(sha256 = "0".repeat(64))
        assertThrows(ApkVerificationException::class.java) { runBlocking { downloader().download(wrong) { _, _ -> } } }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        assertNull(downloader().existing(wrong))
    }

    @Test
    fun `without a digest the published size is the check`() {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val truncated = update(sha256 = null, size = bytes.size + 1L)
        assertThrows(ApkVerificationException::class.java) { runBlocking { downloader().download(truncated) { _, _ -> } } }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val ok = runBlocking { downloader().download(update(sha256 = null)) { _, _ -> } }
        assertArrayEquals(bytes, ok.readBytes())
    }

    @Test
    fun `an HTTP failure is an IOException, not a verification failure`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val error = assertThrows(java.io.IOException::class.java) { runBlocking { downloader().download(update()) { _, _ -> } } }
        assertFalse(error is ApkVerificationException)
    }

    @Test
    fun `existing only trusts a whole file, and a new download clears the old one`() = runBlocking {
        dir.mkdirs()
        File(dir, "Rizx-1.0.5-release.apk").writeBytes(ByteArray(10))
        File(dir, "Rizx-1.1.0-release.apk").writeBytes(ByteArray(10)) // wrong size: torn
        assertNull(downloader().existing(update()))
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        downloader().download(update()) { _, _ -> }
        assertEquals(listOf("Rizx-1.1.0-release.apk"), dir.list()!!.toList())
    }

    @Test
    fun `an attached name with a path is not honoured`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val file = downloader().download(update(name = "../evil.apk")) { _, _ -> }
        assertEquals(dir, file.parentFile)
        assertEquals("evil.apk", file.name)
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val fallback = downloader().download(update(name = "notes.txt")) { _, _ -> }
        assertEquals("Rizx-1.1.0.apk", fallback.name)
    }
}
