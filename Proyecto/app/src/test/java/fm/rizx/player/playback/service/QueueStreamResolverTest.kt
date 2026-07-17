package fm.rizx.player.playback.service

import androidx.media3.common.util.UnstableApi
import fm.rizx.player.domain.model.PlaybackResolverSettings
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.QueueItem
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.DownloadRepository
import fm.rizx.player.domain.repository.LocalLibraryRepository
import fm.rizx.player.domain.repository.QueueRepository
import fm.rizx.player.domain.usecase.CandidateResult
import fm.rizx.player.domain.usecase.StreamingResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the cache-aware core ([QueueStreamResolver.resolveCached]) — what makes re-play / skip / seek
 * near-instant. The Media3 `resolveDataSpec` wrapper (Uri/DataSpec) and the async `warm` prefetch are
 * verified on-device.
 */
@UnstableApi
class QueueStreamResolverTest {

    private val ref = ProviderRef("deezer", "1")
    private val item = QueueItem(
        id = "q0",
        track = Track(title = "Song", source = ref),
        addedAtIso = "2026-01-01T00:00:00Z",
    )
    private val candidate = StreamCandidate(id = "c1", title = "Song", source = ref)
    private val stream = Stream(url = "https://cdn/audio", protocol = StreamProtocol.HTTPS, source = ref)

    private fun resolverReturningStream(): StreamingResolver {
        val r = mockk<StreamingResolver>()
        coEvery { r.resolveCandidatesForTrack(any()) } returns CandidateResult.Success(listOf(candidate))
        coEvery { r.resolveStreamForCandidate(candidate) } returns candidate.copy(stream = stream)
        return r
    }

    /** Nothing downloaded — the default for the pre-existing cache tests. */
    private fun noDownloads() = mockk<DownloadRepository> {
        every { localStream(any()) } returns null
    }

    /** No on-device library — the default; local playback is exercised by its own test. */
    private fun noLocal() = mockk<LocalLibraryRepository> {
        every { localStream(any()) } returns null
    }

    private fun subject(
        resolver: StreamingResolver,
        settings: PlaybackResolverSettings = PlaybackResolverSettings(),
        downloads: DownloadRepository = noDownloads(),
        library: LocalLibraryRepository = noLocal(),
    ) = QueueStreamResolver(mockk<QueueRepository>(relaxed = true), resolver, settings, downloads, library)

    @Test
    fun `resolves once then serves the cached stream without re-resolving`() = runBlocking {
        val resolver = resolverReturningStream()
        val qsr = subject(resolver)

        assertEquals(stream, qsr.resolveCached(item))
        assertEquals(stream, qsr.resolveCached(item)) // 2nd call must be a cache hit

        coVerify(exactly = 1) { resolver.resolveCandidatesForTrack(any()) }
    }

    @Test
    fun `re-resolves once the cached stream has expired`() = runBlocking {
        val resolver = resolverReturningStream()
        // streamExpiryMs = 0 ⇒ every cached entry is immediately stale, forcing a fresh resolve each time.
        val qsr = subject(resolver, PlaybackResolverSettings(streamExpiryMs = 0L))

        qsr.resolveCached(item)
        qsr.resolveCached(item)

        coVerify(exactly = 2) { resolver.resolveCandidatesForTrack(any()) }
    }

    @Test
    fun `returns null when no candidate yields a stream`() = runBlocking {
        val resolver = mockk<StreamingResolver> {
            coEvery { resolveCandidatesForTrack(any()) } returns CandidateResult.Success(emptyList())
        }
        assertEquals(null, subject(resolver).resolveCached(item))
    }

    // ---- Offline: a downloaded track must never touch the network ----

    private val localStream = Stream(
        url = "file:///data/audio/deezer_1.m4a",
        protocol = StreamProtocol.FILE,
        source = ref,
    )

    private fun downloaded() = mockk<DownloadRepository> {
        every { localStream(any()) } returns this@QueueStreamResolverTest.localStream
    }

    @Test
    fun `a downloaded track plays from disk without touching the network`() = runBlocking {
        val resolver = resolverReturningStream()

        val resolved = subject(resolver, downloads = downloaded()).resolveCached(item)

        assertEquals(localStream, resolved)
        // The whole offline promise: no candidate search, no URL resolution, nothing to fail offline.
        coVerify(exactly = 0) { resolver.resolveCandidatesForTrack(any()) }
    }

    @Test
    fun `an on-device library track plays its content uri without touching the network`() = runBlocking {
        val resolver = resolverReturningStream()
        val localUri = Stream(url = "content://media/external/audio/media/42", protocol = StreamProtocol.FILE, source = ref)
        val library = mockk<LocalLibraryRepository> { every { localStream(any()) } returns localUri }

        val resolved = subject(resolver, library = library).resolveCached(item)

        assertEquals(localUri, resolved)
        coVerify(exactly = 0) { resolver.resolveCandidatesForTrack(any()) } // never searches the network
    }

    @Test
    fun `a local file never enters the expiring stream cache`() = runBlocking {
        val resolver = resolverReturningStream()
        val downloads = mockk<DownloadRepository>()
        every { downloads.localStream(any()) } returns localStream
        val qsr = subject(resolver, downloads = downloads)

        qsr.resolveCached(item) // would be cached here if the short-circuit sat after cachePut
        every { downloads.localStream(any()) } returns null // the download is deleted

        // A cached local URL would be served for hours after the file was gone. Instead: back to network.
        assertEquals(stream, qsr.resolveCached(item))
        coVerify(exactly = 1) { resolver.resolveCandidatesForTrack(any()) }
    }

    @Test
    fun `a vanished download falls back to the network instead of erroring`() = runBlocking {
        val resolver = resolverReturningStream()
        // The file was deleted, app data cleared, or storage evicted it: localStream returns null.
        val qsr = subject(resolver, downloads = noDownloads())

        assertEquals(stream, qsr.resolveCached(item))
    }

    @Test
    fun `a downloaded track is not prefetched`() = runBlocking {
        val resolver = resolverReturningStream()

        subject(resolver, downloads = downloaded()).warm(listOf(item))

        // warm() launches into its own scope; give it a chance to (wrongly) do work.
        delay(50)
        coVerify(exactly = 0) { resolver.resolveCandidatesForTrack(any()) }
    }
}
