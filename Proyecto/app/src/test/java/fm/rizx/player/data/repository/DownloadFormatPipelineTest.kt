package fm.rizx.player.data.repository

import fm.rizx.player.FakeSettingsRepository
import fm.rizx.player.data.download.Mp3Transcoder
import fm.rizx.player.data.download.NoopDownloadNotifier
import fm.rizx.player.data.download.TrackDownloader
import fm.rizx.player.data.local.store.DownloadIndexStore
import fm.rizx.player.domain.model.DownloadFormat
import fm.rizx.player.domain.model.DownloadStatus
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.CachedAudioReader
import fm.rizx.player.domain.usecase.CandidateResult
import fm.rizx.player.domain.usecase.StreamingResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.time.Instant

/**
 * The download pipeline's new axes: which format is fetched, when the MP3 conversion runs, when the
 * byte-cache substitutes for the network, and how many songs move at once.
 *
 * The base contract — validate, rename, index under the track's ref — is `DownloadRepositoryTest`'s
 * job and is deliberately not repeated here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadFormatPipelineTest {

    @get:Rule val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val trackRef = ProviderRef("deezer", "123")
    private val track = Track(title = "Velvet Hours", source = trackRef)
    private val audio = ByteArray(2048) { it.toByte() }
    private val mp3Bytes = ByteArray(512) { (it * 3).toByte() }

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun audioBody(type: String = "audio/mp4") =
        MockResponse().setHeader("Content-Type", type).setBody(Buffer().write(audio))

    private fun stream(container: String = "m4a") = Stream(
        url = server.url("/audio").toString(),
        protocol = StreamProtocol.HTTPS,
        mimeType = if (container == "mp3") "audio/mpeg" else "audio/mp4",
        container = container,
        source = ProviderRef("youtube", "candidate-abc"),
    )

    private fun resolverYielding(stream: Stream?): StreamingResolver {
        val candidate = StreamCandidate(id = "c1", title = "Velvet Hours", source = ProviderRef("youtube", "c1"))
        return mockk {
            coEvery { resolveCandidatesForTrack(any()) } returns CandidateResult.Success(listOf(candidate))
            coEvery { resolveStreamForCandidate(candidate, any(), any()) } returns candidate.copy(stream = stream)
        }
    }

    /**
     * A transcoder that "converts" by writing [mp3Bytes]. A subclass rather than a mock: the pipeline
     * calls it across real suspension points, and a plain object with plain code has no machinery to
     * disagree with the test scheduler about when the continuation runs.
     */
    private class FakeTranscoder(
        private val bytes: ByteArray,
        private val onRun: suspend () -> Unit = {},
    ) : Mp3Transcoder() {
        var calls = 0
        override suspend fun transcode(source: File, target: File) {
            calls++
            onRun()
            target.writeBytes(bytes)
        }
    }

    private class FakeCachedAudio(
        private val buckets: Map<String, ByteArray> = emptyMap(),
    ) : CachedAudioReader {
        override fun fullyCachedCodecs(identityKey: String): List<String> = buckets.keys.toList()
        override suspend fun copyTo(identityKey: String, codec: String, sink: OutputStream): Boolean {
            val bytes = buckets[codec] ?: return false
            sink.write(bytes)
            return true
        }
    }

    private fun audioDir() = File(temp.root, "audio")

    private fun subject(
        resolver: StreamingResolver,
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        transcoder: Mp3Transcoder? = null,
        cachedAudio: CachedAudioReader? = null,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = UnconfinedTestDispatcher(),
    ) = DownloadRepositoryImpl(
        store = DownloadIndexStore(File(temp.root, "downloads.json")),
        downloader = TrackDownloader(OkHttpClient(), audioDir(), dispatcher),
        resolver = resolver,
        exporter = mockk(relaxed = true),
        notifier = NoopDownloadNotifier,
        now = { Instant.parse("2026-08-01T10:00:00Z") },
        io = dispatcher,
        settings = settings,
        transcoder = transcoder,
        cachedAudio = cachedAudio,
    )

    // ---- the format that gets fetched ----

    @Test
    fun `the Settings format is what a plain download uses`() = runTest {
        server.enqueue(audioBody())
        val settings = FakeSettingsRepository().apply { downloadFormatFlow.value = DownloadFormat.OPUS }
        val resolver = resolverYielding(stream(container = "webm"))
        val repo = subject(resolver, settings)

        repo.download(track)

        assertEquals("webm", repo.downloads.value.single().container)
        coVerify { resolver.resolveStreamForCandidate(any(), true, DownloadFormat.OPUS) }
    }

    @Test
    fun `an explicit format overrides the Settings default for that one download`() = runTest {
        server.enqueue(audioBody())
        val settings = FakeSettingsRepository().apply { downloadFormatFlow.value = DownloadFormat.MP3 }
        val resolver = resolverYielding(stream())
        val repo = subject(resolver, settings)

        repo.download(track, DownloadFormat.ORIGINAL)

        assertEquals("m4a", repo.downloads.value.single().container)
        coVerify { resolver.resolveStreamForCandidate(any(), true, DownloadFormat.ORIGINAL) }
    }

    // ---- MP3 conversion ----

    @Test
    fun `MP3 format converts the fetched file and indexes the mp3`() = runTest {
        server.enqueue(audioBody())
        val repo = subject(resolverYielding(stream()), transcoder = FakeTranscoder(mp3Bytes))

        repo.download(track, DownloadFormat.MP3)

        val entry = repo.downloads.value.single()
        assertEquals("mp3", entry.container)
        assertEquals("audio/mpeg", entry.mimeType)
        assertTrue(entry.fileName.endsWith(".mp3"))
        assertTrue(File(audioDir(), entry.fileName).readBytes().contentEquals(mp3Bytes))
        // The fetched .m4a must not survive as an orphan next to the .mp3 that replaced it.
        assertEquals(listOf(entry.fileName), audioDir().list()!!.toList())
    }

    @Test
    fun `while converting, the row says CONVERTING`() = runTest {
        server.enqueue(audioBody())
        // A gated transcoder parks the pipeline mid-conversion, so the state can be sampled *at rest*
        // rather than raced — the derived `states` flow settles on advanceUntilIdle.
        val gate = CompletableDeferred<Unit>()
        val transcoder = FakeTranscoder(mp3Bytes, onRun = { gate.await() })
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = subject(resolverYielding(stream()), transcoder = transcoder, dispatcher = dispatcher)

        repo.download(track, DownloadFormat.MP3)
        advanceUntilIdle()

        assertEquals(DownloadStatus.CONVERTING, repo.states.value.getValue("deezer:123").status)

        gate.complete(Unit)
        // Suspend on the flow rather than sampling after advanceUntilIdle: the index write behind
        // COMPLETE hops through real Dispatchers.IO, which the test scheduler cannot fast-forward.
        val terminal = repo.states.first { it["deezer:123"]?.status == DownloadStatus.COMPLETE }
        assertEquals(DownloadStatus.COMPLETE, terminal.getValue("deezer:123").status)
    }

    @Test
    fun `a source that is already MP3 is not re-encoded`() = runTest {
        server.enqueue(audioBody(type = "audio/mpeg"))
        val transcoder = FakeTranscoder(mp3Bytes)
        val repo = subject(resolverYielding(stream(container = "mp3")), transcoder = transcoder)

        repo.download(track, DownloadFormat.MP3)

        assertEquals("mp3", repo.downloads.value.single().container)
        assertEquals("a lossy→lossy re-encode with nothing to gain", 0, transcoder.calls)
    }

    @Test
    fun `a conversion failure marks FAILED and leaves no file and no index entry`() = runTest {
        server.enqueue(audioBody())
        val transcoder = FakeTranscoder(mp3Bytes, onRun = { error("decoder died") })
        val repo = subject(resolverYielding(stream()), transcoder = transcoder)

        repo.download(track, DownloadFormat.MP3)

        assertTrue(repo.downloads.value.isEmpty())
        assertEquals(DownloadStatus.FAILED, repo.states.value.getValue("deezer:123").status)
        assertEquals(emptyList<String>(), audioDir().list()!!.toList())
    }

    // ---- FLAC fallback ----

    @Test
    fun `FLAC format with no verified FLAC falls back to the original download`() = runTest {
        server.enqueue(audioBody())
        val resolver = resolverYielding(stream())
        // lossless = null: the resolver isn't even installed. The download must still succeed.
        val repo = subject(resolver)

        repo.download(track, DownloadFormat.FLAC)

        assertEquals("m4a", repo.downloads.value.single().container)
        coVerify { resolver.resolveStreamForCandidate(any(), true, DownloadFormat.FLAC) }
    }

    // ---- the byte cache as a source ----

    @Test
    fun `a fully cached song is adopted with zero network requests`() = runTest {
        val repo = subject(
            resolverYielding(stream()),
            cachedAudio = FakeCachedAudio(mapOf("m4a" to audio)),
        )

        repo.download(track)

        assertEquals(0, server.requestCount)
        val entry = repo.downloads.value.single()
        assertEquals("m4a", entry.container)
        assertTrue(File(audioDir(), entry.fileName).readBytes().contentEquals(audio))
    }

    @Test
    fun `ORIGINAL refuses a cached opus bucket — it would change what Original saves`() = runTest {
        server.enqueue(audioBody())
        val repo = subject(
            resolverYielding(stream()),
            cachedAudio = FakeCachedAudio(mapOf("webm opus" to audio)),
        )

        repo.download(track, DownloadFormat.ORIGINAL)

        // The cached bucket exists but is the wrong shape for ORIGINAL: the network path runs instead.
        assertEquals(1, server.requestCount)
        assertEquals("m4a", repo.downloads.value.single().container)
    }

    @Test
    fun `OPUS adopts the cached opus bucket as webm`() = runTest {
        val repo = subject(
            resolverYielding(stream(container = "webm")),
            cachedAudio = FakeCachedAudio(mapOf("webm opus" to audio)),
        )

        repo.download(track, DownloadFormat.OPUS)

        assertEquals(0, server.requestCount)
        assertEquals("webm", repo.downloads.value.single().container)
    }

    @Test
    fun `a cache copy that dies mid-read falls back to the network`() = runTest {
        server.enqueue(audioBody())
        val flaky = object : CachedAudioReader {
            override fun fullyCachedCodecs(identityKey: String) = listOf("m4a")
            override suspend fun copyTo(identityKey: String, codec: String, sink: OutputStream): Boolean {
                sink.write(ByteArray(64)) // partial write, then the eviction race loses
                return false
            }
        }
        val repo = subject(resolverYielding(stream()), cachedAudio = flaky)

        repo.download(track)

        assertEquals(1, server.requestCount)
        assertEquals(audio.size.toLong(), repo.downloads.value.single().sizeBytes)
    }

    // ---- parallelism ----

    @Test
    fun `two songs download at once and the third waits for a slot`() = runTest {
        val entered = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val resolver = mockk<StreamingResolver> {
            coEvery { resolveCandidatesForTrack(any()) } coAnswers {
                entered += firstArg<Track>().source.id
                gate.await()
                CandidateResult.Failure("test never resolves")
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = subject(resolver, dispatcher = dispatcher)

        repo.download(track.copy(source = ProviderRef("deezer", "1")))
        repo.download(track.copy(source = ProviderRef("deezer", "2")))
        repo.download(track.copy(source = ProviderRef("deezer", "3")))
        advanceUntilIdle()

        assertEquals("exactly two slots", listOf("1", "2"), entered)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("1", "2", "3"), entered)
    }
}
