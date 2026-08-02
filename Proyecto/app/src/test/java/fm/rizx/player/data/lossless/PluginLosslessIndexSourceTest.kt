package fm.rizx.player.data.lossless

import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.data.repository.StreamingRepositoryImpl
import fm.rizx.player.domain.lossless.LosslessIndexItem
import fm.rizx.player.domain.lossless.LosslessIndexProvider
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.EnabledProviderStore
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.StreamingProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the index comes from, and — just as importantly — where it must **not** be consulted.
 *
 * An index plugin registers as a streaming provider because that is the contract the runtime bridges.
 * Left in the ordinary fallback chain it would be asked about every song in every mode, which is exactly
 * the "off costs nothing" promise broken. The exclusion is asserted here rather than assumed.
 */
class PluginLosslessIndexSourceTest {

    @Test
    fun `with no index plugin registered, nothing is available`() {
        val registry = DefaultProviderRegistry()
        registry.register(OrdinaryProvider())

        assertFalse(PluginLosslessIndexSource(registry).isAvailable())
    }

    @Test
    fun `installing an index plugin makes it available without a restart`() {
        // The real sequence: Settings → Plugins → install → back. The registry is read per call for
        // exactly this reason.
        val registry = DefaultProviderRegistry()
        val source = PluginLosslessIndexSource(registry)

        assertFalse(source.isAvailable())
        registry.register(IndexProvider(rows = listOf(row("https://host/a.flac"))))
        assertTrue(source.isAvailable())
    }

    @Test
    fun `rows come back from every registered index`() = runTest {
        val registry = DefaultProviderRegistry()
        registry.register(IndexProvider(id = "one", rows = listOf(row("https://host/a.flac"))))
        registry.register(IndexProvider(id = "two", rows = listOf(row("https://host/b.flac"))))

        val rows = PluginLosslessIndexSource(registry).lookup(track())

        assertEquals(2, rows.size)
    }

    @Test
    fun `the same file offered twice is one candidate, not an ambiguous tie with itself`() = runTest {
        val registry = DefaultProviderRegistry()
        registry.register(IndexProvider(id = "one", rows = listOf(row("https://host/a.flac"))))
        registry.register(IndexProvider(id = "two", rows = listOf(row("https://host/a.flac"))))

        assertEquals(1, PluginLosslessIndexSource(registry).lookup(track()).size)
    }

    @Test
    fun `one broken index does not cost the others their turn`() = runTest {
        val registry = DefaultProviderRegistry()
        registry.register(IndexProvider(id = "broken", failure = IllegalStateException("boom")))
        registry.register(IndexProvider(id = "fine", rows = listOf(row("https://host/a.flac"))))

        assertEquals(1, PluginLosslessIndexSource(registry).lookup(track()).size)
    }

    @Test
    fun `an index-only provider is kept out of the ordinary streaming chain`() = runTest {
        // The whole point: in Standard mode nothing should ever ask a community index for a stream.
        val registry = DefaultProviderRegistry()
        val ordinary = OrdinaryProvider()
        registry.register(ordinary)
        val index = IndexProvider(rows = listOf(row("https://host/a.flac")))
        registry.register(index)

        val repo = StreamingRepositoryImpl(registry, alwaysEnabled())
        val candidates = repo.searchForTrack(track())

        assertEquals(listOf("ordinary-candidate"), candidates.map { it.id })
        assertEquals("the index must not be asked for ordinary streams", 0, index.searches)
    }

    // ---- fixtures ----

    private fun row(url: String) = LosslessIndexItem(song = "Pepas", artist = "Farruko", url = url)

    private fun track() = Track(title = "Pepas", source = ProviderRef("deezer", "track:1"))

    private fun alwaysEnabled() = object : EnabledProviderStore {
        override fun isEnabled(id: String): Flow<Boolean> = flowOf(true)
        override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun snapshot(ids: Collection<String>): Map<String, Boolean> = ids.associateWith { true }
    }

    /** What a plugin whose only job is the index looks like: an index, and no ordinary search. */
    private class IndexProvider(
        override val id: String = "lossless-index",
        private val rows: List<LosslessIndexItem> = emptyList(),
        private val failure: Exception? = null,
    ) : StreamingProvider, LosslessIndexProvider {
        override val name = "Lossless index"
        override val kind = ProviderKind.STREAMING
        override val hasLosslessIndex = true
        override val isLosslessOnly = true

        var searches = 0
            private set

        override suspend fun losslessLookup(track: Track): List<LosslessIndexItem> {
            failure?.let { throw it }
            return rows
        }

        override suspend fun searchForTrack(track: Track): List<StreamCandidate> {
            searches++
            return emptyList()
        }

        override suspend fun getStreamUrl(candidate: StreamCandidate): Stream =
            error("an index-only provider should never be asked to resolve a stream")
    }

    private class OrdinaryProvider : StreamingProvider {
        override val id = "ordinary"
        override val name = "Ordinary"
        override val kind = ProviderKind.STREAMING

        override suspend fun searchForTrack(track: Track): List<StreamCandidate> =
            listOf(StreamCandidate(id = "ordinary-candidate", title = track.title, source = ProviderRef(id, "1")))

        override suspend fun getStreamUrl(candidate: StreamCandidate): Stream =
            Stream(url = "https://cdn/audio", protocol = StreamProtocol.HTTPS, source = candidate.source)
    }
}
