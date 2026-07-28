package fm.rizx.player.data.repository

import fm.rizx.player.data.download.ExportedFile
import fm.rizx.player.data.download.MediaStoreExporter
import fm.rizx.player.data.download.NoopDownloadNotifier
import fm.rizx.player.data.download.TrackDownloader
import fm.rizx.player.data.local.store.DownloadIndexStore
import fm.rizx.player.domain.model.DownloadStatus
import fm.rizx.player.domain.model.DownloadedTrack
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.usecase.CandidateResult
import fm.rizx.player.domain.usecase.StreamingResolver
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRepositoryTest {

    @get:Rule val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val trackRef = ProviderRef("deezer", "123")
    private val track = Track(title = "Velvet Hours", source = trackRef)
    private val audio = ByteArray(2048) { it.toByte() }

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun audioBody(bytes: ByteArray = audio) =
        MockResponse().setHeader("Content-Type", "audio/mp4").setBody(Buffer().write(bytes))

    /** A stream whose `source` is the *streaming* provider — deliberately different from `track.source`. */
    private fun stream(
        provider: String = "youtube",
        container: String? = "m4a",
        protocol: StreamProtocol = StreamProtocol.HTTPS,
        path: String = "/audio",
    ) = Stream(
        url = server.url(path).toString(),
        protocol = protocol,
        mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
        container = container,
        source = ProviderRef(provider, "candidate-abc"),
    )

    private fun resolverYielding(stream: Stream?): StreamingResolver {
        val candidate = StreamCandidate(id = "c1", title = "Velvet Hours", source = ProviderRef("youtube", "c1"))
        return mockk<StreamingResolver> {
            coEvery { resolveCandidatesForTrack(any()) } returns CandidateResult.Success(listOf(candidate))
            // forDownload = true: a download asks for a container it can write tags into.
            coEvery { resolveStreamForCandidate(candidate, any()) } returns candidate.copy(stream = stream)
        }
    }

    /** Shared across instances so a "restart" test sees the same folder the first instance wrote to. */
    private fun audioDir() = File(temp.root, "audio")

    private fun subject(
        resolver: StreamingResolver,
        exporter: MediaStoreExporter = mockk(relaxed = true),
    ): DownloadRepositoryImpl {
        val dispatcher = UnconfinedTestDispatcher()
        return DownloadRepositoryImpl(
            store = DownloadIndexStore(File(temp.root, "downloads.json")),
            downloader = TrackDownloader(OkHttpClient(), audioDir(), dispatcher),
            resolver = resolver,
            exporter = exporter,
            notifier = NoopDownloadNotifier,
            now = { Instant.parse("2026-07-16T10:00:00Z") },
            io = dispatcher,
        )
    }

    @Test
    fun `downloads the bytes and indexes the track`() = runTest {
        server.enqueue(audioBody())
        val repo = subject(resolverYielding(stream()))

        repo.download(track)

        val entry = repo.downloads.value.single()
        assertEquals(track, entry.track)
        assertEquals(audio.size.toLong(), entry.sizeBytes)
        assertEquals("m4a", entry.container)
        assertEquals(audio.size.toLong(), File(audioDir(), entry.fileName).length())
    }

    @Test
    fun `indexes under the track's ref, not the stream's`() = runTest {
        server.enqueue(audioBody())
        // A Deezer-discovered track played via Audius: track.source is deezer:123 but the resolved
        // stream's source is the streaming provider. Keying by the stream would make this download
        // invisible from the playlist that asked for it.
        val repo = subject(resolverYielding(stream(provider = "audius-streaming")))

        repo.download(track)

        assertEquals(setOf("deezer:123"), repo.states.value.keys)
        assertNotNull(repo.localStream(track))
    }

    @Test
    fun `serves a downloaded track as a local file stream`() = runTest {
        server.enqueue(audioBody())
        val repo = subject(resolverYielding(stream()))
        repo.download(track)

        val local = repo.localStream(track)!!

        assertEquals(StreamProtocol.FILE, local.protocol)
        assertTrue(local.url.startsWith("file:"))
        assertEquals(trackRef, local.source)
    }

    @Test
    fun `falls back to the network when the file has vanished`() = runTest {
        server.enqueue(audioBody())
        val repo = subject(resolverYielding(stream()))
        repo.download(track)

        File(audioDir(), repo.downloads.value.single().fileName).delete()

        // Null is the whole fallback: the resolver just does its normal network resolve.
        assertNull(repo.localStream(track))
    }

    @Test
    fun `an undownloaded track has no local stream`() = runTest {
        assertNull(subject(resolverYielding(stream())).localStream(track))
    }

    @Test
    fun `rejects iTunes because its preview is 30s but claims the full duration`() = runTest {
        val repo = subject(resolverYielding(stream(provider = "itunes-streaming")))

        repo.download(track)

        assertTrue(repo.downloads.value.isEmpty())
        assertEquals(DownloadStatus.FAILED, repo.states.value.getValue("deezer:123").status)
        assertEquals(0, server.requestCount) // rejected before a byte is fetched
    }

    @Test
    fun `rejects an HLS manifest because it is not audio`() = runTest {
        val repo = subject(resolverYielding(stream(protocol = StreamProtocol.HLS)))

        repo.download(track)

        assertTrue(repo.downloads.value.isEmpty())
        assertEquals(DownloadStatus.FAILED, repo.states.value.getValue("deezer:123").status)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `follows a redirect to a CDN, as Audius requires`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/cdn").toString()))
        server.enqueue(audioBody())
        val repo = subject(resolverYielding(stream(provider = "audius-streaming", container = "mp3")))

        repo.download(track)

        assertEquals(audio.size.toLong(), repo.downloads.value.single().sizeBytes)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a server error leaves nothing indexed and no partial file`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val repo = subject(resolverYielding(stream()))

        repo.download(track)

        assertTrue(repo.downloads.value.isEmpty())
        assertEquals(DownloadStatus.FAILED, repo.states.value.getValue("deezer:123").status)
        assertEquals(emptyList<String>(), audioDir().list()!!.toList())
    }

    @Test
    fun `a truncated body is discarded instead of becoming a half-playable song`() = runTest {
        // Declares more than it sends. Without the content-length check the file would play for a while
        // and then die — permanently, since the resolver prefers a local file over the network.
        // setHeader must come *after* setBody: setBody sets Content-Length itself and would clobber it.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "audio/mp4")
                .setBody(Buffer().write(audio))
                .setHeader("Content-Length", (audio.size * 2).toString()),
        )
        val repo = subject(resolverYielding(stream()))

        repo.download(track)

        assertTrue(repo.downloads.value.isEmpty())
        assertEquals(emptyList<String>(), audioDir().list()!!.toList())
    }

    @Test
    fun `an error page served as HTTP 200 is not saved as the song`() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>nope</html>"))
        val repo = subject(resolverYielding(stream()))

        repo.download(track)

        assertTrue(repo.downloads.value.isEmpty())
    }

    @Test
    fun `a track with no playable source fails cleanly`() = runTest {
        val repo = subject(resolverYielding(null))

        repo.download(track)

        assertTrue(repo.downloads.value.isEmpty())
        assertEquals(DownloadStatus.FAILED, repo.states.value.getValue("deezer:123").status)
    }

    @Test
    fun `downloading twice fetches once`() = runTest {
        server.enqueue(audioBody())
        val repo = subject(resolverYielding(stream()))

        repo.download(track)
        repo.download(track)

        assertEquals(1, repo.downloads.value.size)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `delete removes the file and the entry but keeps the song streamable`() = runTest {
        server.enqueue(audioBody())
        val repo = subject(resolverYielding(stream()))
        repo.download(track)
        val fileName = repo.downloads.value.single().fileName

        repo.delete("deezer:123")

        assertTrue(repo.downloads.value.isEmpty())
        assertFalse(File(audioDir(), fileName).exists())
        assertNull(repo.localStream(track)) // ⇒ the resolver streams it again
    }

    @Test
    fun `the index survives a restart`() = runTest {
        server.enqueue(audioBody())
        subject(resolverYielding(stream())).download(track)

        val reopened = subject(resolverYielding(stream()))

        assertEquals(listOf(trackRef), reopened.downloads.value.map { it.track.source })
        assertNotNull(reopened.localStream(track))
    }

    @Test
    fun `export records the uri so the row can show it was exported`() = runTest {
        server.enqueue(audioBody())
        val exporter = mockk<MediaStoreExporter> {
            coEvery { export(any<DownloadedTrack>(), any<File>()) } returns
                Result.success(ExportedFile("content://media/audio/9", "Maya Sol - Velvet Hours.m4a"))
        }
        val repo = subject(resolverYielding(stream()), exporter)
        repo.download(track)

        val name = repo.export("deezer:123").getOrThrow()

        assertEquals("Maya Sol - Velvet Hours.m4a", name)
        assertEquals("content://media/audio/9", repo.downloads.value.single().exportedUri)
    }
}
