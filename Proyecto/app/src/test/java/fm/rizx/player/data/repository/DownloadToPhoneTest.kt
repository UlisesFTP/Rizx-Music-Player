package fm.rizx.player.data.repository

import fm.rizx.player.FakeSettingsRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * Publishing a download into the phone's own Music folder.
 *
 * The thing worth protecting here is that the copy is a *courtesy*: it must never decide whether the
 * download succeeded, and it must never leave a second file behind for a song that is already there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadToPhoneTest {

    @get:Rule val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val trackRef = ProviderRef("deezer", "123")
    private val track = Track(title = "Velvet Hours", source = trackRef)
    private val audio = ByteArray(2048) { it.toByte() }

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    /** Records what was published, and can pretend the user deleted it afterwards. */
    private class FakeExporter(
        private val fail: Boolean = false,
        var stillOnPhone: Boolean = true,
    ) : MediaStoreExporter {
        val exported = mutableListOf<String>()

        override suspend fun export(entry: DownloadedTrack, file: File): Result<ExportedFile> {
            if (fail) return Result.failure(IllegalStateException("MediaStore said no"))
            exported += entry.fileName
            return Result.success(ExportedFile("content://media/audio/${exported.size}", "Velvet Hours.m4a"))
        }

        override suspend fun exists(uri: String): Boolean = stillOnPhone
    }

    private fun audioBody() =
        MockResponse().setHeader("Content-Type", "audio/mp4").setBody(Buffer().write(audio))

    private fun resolverYielding(): StreamingResolver {
        val candidate = StreamCandidate(id = "c1", title = "Velvet Hours", source = ProviderRef("youtube", "c1"))
        val stream = Stream(
            url = server.url("/audio").toString(),
            protocol = StreamProtocol.HTTPS,
            mimeType = "audio/mp4",
            container = "m4a",
            source = ProviderRef("youtube", "candidate-abc"),
        )
        return mockk {
            coEvery { resolveCandidatesForTrack(any()) } returns CandidateResult.Success(listOf(candidate))
            coEvery { resolveStreamForCandidate(candidate, any(), any()) } returns candidate.copy(stream = stream)
        }
    }

    private fun subject(exporter: MediaStoreExporter, settings: FakeSettingsRepository) =
        DownloadRepositoryImpl(
            store = DownloadIndexStore(File(temp.root, "downloads.json")),
            downloader = TrackDownloader(OkHttpClient(), File(temp.root, "audio"), UnconfinedTestDispatcher()),
            resolver = resolverYielding(),
            exporter = exporter,
            notifier = NoopDownloadNotifier,
            now = { Instant.parse("2026-08-03T10:00:00Z") },
            io = UnconfinedTestDispatcher(),
            settings = settings,
        )

    private fun settings(saveToPhone: Boolean?) =
        FakeSettingsRepository().apply { saveDownloadsToPhoneFlow.value = saveToPhone }

    @Test
    fun `the copy is made when the user asked for it`() = runTest {
        server.enqueue(audioBody())
        val exporter = FakeExporter()
        val repo = subject(exporter, settings(saveToPhone = true))

        repo.download(track)

        // Suspend on the copy landing, not on the download: publishing happens after the entry is
        // indexed and writes the index again, and both hops go through real IO.
        val entry = repo.downloads.first { list -> list.singleOrNull()?.exportedUri != null }.single()
        assertEquals(listOf(entry.fileName), exporter.exported)
        assertEquals("content://media/audio/1", entry.exportedUri)
    }

    @Test
    fun `with the setting off the download stays inside the app`() = runTest {
        server.enqueue(audioBody())
        val exporter = FakeExporter()
        val repo = subject(exporter, settings(saveToPhone = false))

        repo.download(track)

        assertEquals(emptyList<String>(), exporter.exported)
        assertNull(repo.downloads.first { it.isNotEmpty() }.single().exportedUri)
    }

    @Test
    fun `never asked behaves like off — nothing leaves the app until the question is answered`() = runTest {
        server.enqueue(audioBody())
        val exporter = FakeExporter()
        val repo = subject(exporter, settings(saveToPhone = null))

        repo.download(track)

        assertEquals(emptyList<String>(), exporter.exported)
    }

    @Test
    fun `a copy that fails does not fail the download`() = runTest {
        server.enqueue(audioBody())
        val repo = subject(FakeExporter(fail = true), settings(saveToPhone = true))

        repo.download(track)

        // Complete, indexed and playable offline — only the phone copy is missing, and the row's own
        // button is the way to try again.
        val entry = repo.downloads.first { it.isNotEmpty() }.single()
        assertNull(entry.exportedUri)
        assertEquals(
            DownloadStatus.COMPLETE,
            repo.states.first { it["deezer:123"]?.status == DownloadStatus.COMPLETE }.getValue("deezer:123").status,
        )
        assertTrue(File(File(temp.root, "audio"), entry.fileName).exists())
    }

    @Test
    fun `saving a song that is already on the phone does not copy it twice`() = runTest {
        server.enqueue(audioBody())
        val exporter = FakeExporter()
        val repo = subject(exporter, settings(saveToPhone = true))
        repo.download(track)
        repo.downloads.first { list -> list.singleOrNull()?.exportedUri != null }

        val result = repo.export("deezer:123")

        // MediaStore answers a repeat insert with "Velvet Hours (1).m4a" rather than refusing it, so a
        // second copy is the failure mode this guards.
        assertTrue(result.isSuccess)
        assertEquals(1, exporter.exported.size)
    }

    @Test
    fun `a copy the user deleted from the phone is made again`() = runTest {
        server.enqueue(audioBody())
        val exporter = FakeExporter()
        val repo = subject(exporter, settings(saveToPhone = true))
        repo.download(track)
        repo.downloads.first { list -> list.singleOrNull()?.exportedUri != null }

        exporter.stillOnPhone = false // deleted from Music/Rizx by hand
        repo.export("deezer:123")

        assertEquals(2, exporter.exported.size)
    }

    @Test
    fun `exporting a song that was never downloaded fails instead of pretending`() = runTest {
        val repo = subject(FakeExporter(), settings(saveToPhone = false))

        assertTrue(repo.export("deezer:nope").isFailure)
    }
}
