package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderHealth
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHealthProbeTest {

    private class FakeMeta(private val delayMs: Long = 0, private val fail: Boolean = false) : MetadataProvider {
        override val id = "m"
        override val kind = ProviderKind.METADATA
        override val name = "m"
        override val searchCapabilities = setOf(SearchCapability.UNIFIED)
        override suspend fun search(params: SearchParams): SearchResults {
            if (delayMs > 0) delay(delayMs)
            if (fail) throw RuntimeException("boom")
            return SearchResults()
        }
    }

    @Test
    fun `a responsive provider reports Ok with measured latency`() = runTest {
        val times = ArrayDeque(listOf(0L, 42_000_000L)) // start, end (nanos) → 42 ms
        val probe = ProviderHealthProbe(nowNanos = { times.removeFirst() })

        val health = probe.probe(FakeMeta())

        assertEquals(ProviderHealth.Ok(42), health)
    }

    @Test
    fun `a slow provider times out to Down`() = runTest {
        val probe = ProviderHealthProbe(timeoutMs = 1_000)

        val health = probe.probe(FakeMeta(delayMs = 10_000))

        assertTrue(health is ProviderHealth.Down)
        assertEquals("timeout", (health as ProviderHealth.Down).reason)
    }

    @Test
    fun `a failing provider reports Down, isolated`() = runTest {
        val probe = ProviderHealthProbe()

        assertTrue(probe.probe(FakeMeta(fail = true)) is ProviderHealth.Down)
    }
}
