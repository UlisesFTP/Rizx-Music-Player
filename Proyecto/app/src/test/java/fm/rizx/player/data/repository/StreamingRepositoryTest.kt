package fm.rizx.player.data.repository

import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.EnabledProviderStore
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.StreamingProvider
import fm.rizx.player.domain.repository.NoStreamingProviderException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingRepositoryTest {

    private class FakeStreaming(
        override val id: String,
        private val results: (Track) -> List<StreamCandidate> = { track ->
            listOf(StreamCandidate(id = "$id-c", title = track.title, source = ProviderRef(id, "$id-c")))
        },
    ) : StreamingProvider {
        override val kind = ProviderKind.STREAMING
        override val name = id
        var searchCalls = 0
        override suspend fun searchForTrack(track: Track): List<StreamCandidate> {
            searchCalls++
            return results(track)
        }
        override suspend fun getStreamUrl(candidate: StreamCandidate): Stream =
            Stream(url = "https://$id/${candidate.id}", protocol = StreamProtocol.HTTPS, source = ProviderRef(id, candidate.id))
    }

    /** Test store: everything enabled unless explicitly disabled (mirrors the DataStore default). */
    private class FakeEnabledStore(private val disabled: Set<String> = emptySet()) : EnabledProviderStore {
        override fun isEnabled(id: String): Flow<Boolean> = flowOf(id !in disabled)
        override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun snapshot(ids: Collection<String>): Map<String, Boolean> =
            ids.associateWith { it !in disabled }
    }

    private fun track() = Track(title = "Track", source = ProviderRef("fake-meta", "tr-1"))

    @Test
    fun `routes to the active streaming provider`() = runTest {
        val registry = DefaultProviderRegistry()
        val provider = FakeStreaming("s1")
        registry.register(provider)
        val repo = StreamingRepositoryImpl(registry, FakeEnabledStore())

        val candidates = repo.searchForTrack(track())

        assertEquals(1, provider.searchCalls)
        assertEquals("s1-c", candidates.single().id)
        assertEquals("https://s1/s1-c", repo.getStreamUrl(candidates.single()).url)
    }

    @Test
    fun `resolves a track against its own streaming provider, not the first in the chain`() = runTest {
        val registry = DefaultProviderRegistry()
        val youtube = FakeStreaming("youtube") // first in the chain; would "match" any title
        val soundcloud = FakeStreaming("soundcloud")
        registry.register(youtube)
        registry.register(soundcloud)
        val repo = StreamingRepositoryImpl(registry, FakeEnabledStore())

        // A track discovered natively ON SoundCloud (Underground tab) — its source.provider is "soundcloud".
        val scTrack = Track(title = "Indie", source = ProviderRef("soundcloud", "https://soundcloud.com/a/b"))
        val candidates = repo.searchForTrack(scTrack)

        // The owner resolved it (playing the exact permalink); the first-in-chain provider was never asked.
        assertEquals(0, youtube.searchCalls)
        assertEquals(1, soundcloud.searchCalls)
        assertEquals("soundcloud-c", candidates.single().id)
    }

    @Test
    fun `a metadata-only track still uses the fallback chain, not a streaming owner`() = runTest {
        val registry = DefaultProviderRegistry()
        val youtube = FakeStreaming("youtube")
        registry.register(youtube)
        val repo = StreamingRepositoryImpl(registry, FakeEnabledStore())

        // track().source is "fake-meta" — no streaming provider owns it, so the chain runs normally.
        val candidates = repo.searchForTrack(track())

        assertEquals(1, youtube.searchCalls)
        assertEquals("youtube-c", candidates.single().id)
    }

    @Test
    fun `falls back to the next provider when the active one returns no candidates`() = runTest {
        val registry = DefaultProviderRegistry()
        val active = FakeStreaming("primary") { emptyList() }
        val fallback = FakeStreaming("fallback")
        registry.register(active) // first-wins → active
        registry.register(fallback)
        val repo = StreamingRepositoryImpl(registry, FakeEnabledStore())

        val candidates = repo.searchForTrack(track())

        assertEquals(1, active.searchCalls)
        assertEquals(1, fallback.searchCalls)
        assertEquals("fallback-c", candidates.single().id)
        // Phase-2 routes back to the provider that produced the candidate, not the active one.
        assertEquals("https://fallback/fallback-c", repo.getStreamUrl(candidates.single()).url)
    }

    @Test
    fun `a broken active provider does not abort the chain`() = runTest {
        val registry = DefaultProviderRegistry()
        val active = FakeStreaming("primary") { error("boom") }
        val fallback = FakeStreaming("fallback")
        registry.register(active)
        registry.register(fallback)
        val repo = StreamingRepositoryImpl(registry, FakeEnabledStore())

        val candidates = repo.searchForTrack(track())

        assertEquals("fallback-c", candidates.single().id)
    }

    @Test
    fun `skips a disabled non-active provider`() = runTest {
        val registry = DefaultProviderRegistry()
        val active = FakeStreaming("primary") { emptyList() }
        val fallback = FakeStreaming("fallback")
        registry.register(active)
        registry.register(fallback)
        val repo = StreamingRepositoryImpl(registry, FakeEnabledStore(disabled = setOf("fallback")))

        val candidates = repo.searchForTrack(track())

        assertEquals(0, fallback.searchCalls) // excluded from the chain
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `throws when no streaming provider is active`() = runTest {
        val repo = StreamingRepositoryImpl(DefaultProviderRegistry(), FakeEnabledStore())
        var thrown = false
        try {
            repo.searchForTrack(track())
        } catch (e: NoStreamingProviderException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
