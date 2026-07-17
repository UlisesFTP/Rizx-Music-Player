package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.PlaybackResolverSettings
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.StreamingRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private fun stream(id: String) = Stream(
    url = "https://stream/$id",
    protocol = StreamProtocol.HTTPS,
    source = ProviderRef("fake-streaming", id),
)

class StreamingResolverTest {

    private val now = 1_700_000_000_000L
    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private class FakeStreamingRepo : StreamingRepository {
        var searchResult: List<StreamCandidate> = emptyList()
        var searchError: Throwable? = null
        var streamError: Throwable? = null
        var streamResult: Stream? = null
        var searchCalls = 0
        var streamCalls = 0

        override suspend fun searchForTrack(track: Track): List<StreamCandidate> {
            searchCalls++
            searchError?.let { throw it }
            return searchResult
        }

        override suspend fun getStreamUrl(candidate: StreamCandidate): Stream {
            streamCalls++
            streamError?.let { throw it }
            return streamResult ?: stream(candidate.id)
        }
    }

    private fun resolver(
        repo: StreamingRepository,
        settings: PlaybackResolverSettings = PlaybackResolverSettings(),
    ) = StreamingResolver(repo, settings, nowEpochMs = { now })

    private fun track(id: String = "tr-1") = Track(title = "Track", source = ProviderRef("fake-meta", id))

    private fun candidate(
        id: String = "c1",
        stream: Stream? = null,
        lastResolvedAtIso: String? = null,
        failed: Boolean = false,
    ) = StreamCandidate(
        id = id,
        title = "Track",
        stream = stream,
        lastResolvedAtIso = lastResolvedAtIso,
        failed = failed,
        source = ProviderRef("fake-streaming", id),
    )

    @Test
    fun `candidate search success returns provider candidates`() = runTest {
        val repo = FakeStreamingRepo().apply { searchResult = listOf(candidate("a"), candidate("b")) }

        val result = resolver(repo).resolveCandidatesForTrack(track())

        assertTrue(result is CandidateResult.Success)
        assertEquals(listOf("a", "b"), (result as CandidateResult.Success).candidates.map { it.id })
        assertEquals(1, repo.searchCalls)
    }

    @Test
    fun `candidate search failure maps to Failure and does not crash`() = runTest {
        val repo = FakeStreamingRepo().apply { searchError = RuntimeException("boom") }

        val result = resolver(repo).resolveCandidatesForTrack(track())

        assertTrue(result is CandidateResult.Failure)
        assertEquals("boom", (result as CandidateResult.Failure).error)
    }

    @Test
    fun `reuses existing usable candidates without searching`() = runTest {
        val repo = FakeStreamingRepo().apply { searchResult = listOf(candidate("fresh-from-provider")) }
        val trackWithCandidates = track().copy(streamCandidates = listOf(candidate("existing")))

        val result = resolver(repo).resolveCandidatesForTrack(trackWithCandidates)

        assertTrue(result is CandidateResult.Success)
        assertEquals(listOf("existing"), (result as CandidateResult.Success).candidates.map { it.id })
        assertEquals(0, repo.searchCalls)
    }

    @Test
    fun `non-expired stream is reused without resolving`() = runTest {
        val repo = FakeStreamingRepo()
        val fresh = candidate(stream = stream("c1"), lastResolvedAtIso = iso(now - 1_000))

        val result = resolver(repo).resolveStreamForCandidate(fresh)

        assertSame(fresh, result)
        assertEquals(0, repo.streamCalls)
    }

    @Test
    fun `expired stream is re-resolved`() = runTest {
        val repo = FakeStreamingRepo()
        val fourHoursAgo = now - 4 * 60 * 60 * 1000L
        val stale = candidate(stream = stream("old"), lastResolvedAtIso = iso(fourHoursAgo))

        val result = resolver(repo).resolveStreamForCandidate(stale)

        assertEquals(1, repo.streamCalls)
        assertEquals("https://stream/c1", result.stream?.url)
        assertEquals(iso(now), result.lastResolvedAtIso)
        assertFalse(result.failed)
    }

    @Test
    fun `unresolved candidate resolves a fresh stream just-in-time`() = runTest {
        val repo = FakeStreamingRepo()

        val result = resolver(repo).resolveStreamForCandidate(candidate())

        assertEquals(1, repo.streamCalls)
        assertEquals("https://stream/c1", result.stream?.url)
        assertEquals(iso(now), result.lastResolvedAtIso)
        assertFalse(result.failed)
    }

    @Test
    fun `resolution retries the configured number of attempts then gives up`() = runTest {
        val repo = FakeStreamingRepo().apply { streamError = RuntimeException("nope") }
        val settings = PlaybackResolverSettings(streamResolutionRetries = 3)

        val result = resolver(repo, settings).resolveStreamForCandidate(candidate())

        assertEquals(3, repo.streamCalls)
        assertTrue(result.failed)
        assertNull(result.stream)
    }

    @Test
    fun `a failed candidate is not retried automatically`() = runTest {
        val repo = FakeStreamingRepo()
        val dead = candidate(failed = true)

        val result = resolver(repo).resolveStreamForCandidate(dead)

        assertSame(dead, result)
        assertEquals(0, repo.streamCalls)
    }
}
