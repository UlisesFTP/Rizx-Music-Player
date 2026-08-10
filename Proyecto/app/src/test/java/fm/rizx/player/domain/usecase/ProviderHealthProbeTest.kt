package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.PlaylistPreview
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.PlaylistProvider
import fm.rizx.player.domain.provider.ProviderHealth
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Plugins screen is allowed to claim about a provider, and how often it may go and find out.
 *
 * The probe is a real search per provider and the screen probes every registered one, so both answers
 * cost network: an honest verdict for kinds it cannot test, and no repeat storm on every visit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderHealthProbeTest {

    private class CountingMetadata(var calls: Int = 0) : MetadataProvider {
        override val id = "counting-metadata"
        override val kind = ProviderKind.METADATA
        override val name = "Counting"
        override val searchCapabilities = emptySet<fm.rizx.player.domain.model.SearchCapability>()
        override suspend fun search(params: SearchParams): SearchResults {
            calls++
            return SearchResults()
        }
    }

    private object SomePlaylists : PlaylistProvider {
        override val id = "some-playlists"
        override val kind = ProviderKind.PLAYLISTS
        override val name = "Some playlists"
        override fun canHandle(url: String) = true
        override suspend fun fetchPlaylist(url: String): PlaylistPreview = error("not called")
    }

    @Test
    fun `a kind with nothing cheap to call reports Unknown, not a green tick`() = runTest {
        // It used to fall through to Ok(0 ms), so every playlist provider showed a healthy badge and a
        // suspiciously perfect latency without anything having been tested.
        assertEquals(ProviderHealth.Unknown, ProviderHealthProbe().probe(SomePlaylists))
    }

    @Test
    fun `a second probe inside the TTL reuses the first answer`() = runTest {
        var now = 0L
        val probe = ProviderHealthProbe(nowNanos = { now }, ttlMs = 60_000)
        val provider = CountingMetadata()

        assertTrue(probe.probe(provider) is ProviderHealth.Ok)
        now += 30_000_000_000 // 30 s
        assertTrue(probe.probe(provider) is ProviderHealth.Ok)

        // The Plugins screen's ViewModel dies on every back-navigation, so without this the user paid
        // one network round-trip per provider every single time they opened the screen.
        assertEquals(1, provider.calls)
    }

    @Test
    fun `once the TTL is past it measures again`() = runTest {
        var now = 0L
        val probe = ProviderHealthProbe(nowNanos = { now }, ttlMs = 60_000)
        val provider = CountingMetadata()

        probe.probe(provider)
        now += 90_000_000_000 // 90 s
        probe.probe(provider)

        assertEquals(2, provider.calls)
    }
}
