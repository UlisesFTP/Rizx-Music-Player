package fm.rizx.player.data.repository

import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.domain.model.DashboardCapability
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.DashboardProvider
import fm.rizx.player.domain.provider.EnabledProviderStore
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRepositoryTest {

    private class FakeEnabled(private val disabled: Set<String> = emptySet()) : EnabledProviderStore {
        override fun isEnabled(id: String) = flowOf(id !in disabled)
        override suspend fun setEnabled(id: String, enabled: Boolean) {}
        override suspend fun snapshot(ids: Collection<String>) = ids.associateWith { it !in disabled }
    }

    private class FakeDash(
        override val id: String,
        private val tracks: List<Track> = emptyList(),
        private val failTracks: Boolean = false,
    ) : DashboardProvider {
        override val kind = ProviderKind.DASHBOARD
        override val name = id
        override val dashboardCapabilities = setOf(DashboardCapability.TOP_TRACKS)
        override suspend fun topTracks(limit: Int): List<Track> =
            if (failTracks) throw RuntimeException("boom") else tracks
    }

    private fun track(title: String) = Track(title = title, source = ProviderRef("deezer", title))

    @Test
    fun `fans out over all registered dashboard providers with attribution`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(FakeDash("d1", tracks = listOf(track("A"))))
            register(FakeDash("d2", tracks = listOf(track("B"))))
        }

        val feed = DashboardRepositoryImpl(registry, FakeEnabled()).homeFeed()

        assertEquals(2, feed.topTracks.size)
        assertEquals(setOf("d1", "d2"), feed.topTracks.map { it.providerId }.toSet())
        assertEquals(listOf("A"), feed.topTracks.first { it.providerId == "d1" }.items.map { it.title })
    }

    @Test
    fun `a failing provider section is isolated and the rest survive`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(FakeDash("bad", failTracks = true))
            register(FakeDash("good", tracks = listOf(track("A"))))
        }

        val feed = DashboardRepositoryImpl(registry, FakeEnabled()).homeFeed()

        // The failing provider contributes nothing; the healthy one still appears.
        assertEquals(listOf("good"), feed.topTracks.map { it.providerId })
    }

    @Test
    fun `no dashboard providers yields an empty feed`() = runTest {
        val feed = DashboardRepositoryImpl(DefaultProviderRegistry(), FakeEnabled()).homeFeed()
        assertTrue(feed.isEmpty)
    }

    @Test
    fun `a disabled provider is excluded from the fan-out`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(FakeDash("off", tracks = listOf(track("A"))))
            register(FakeDash("on", tracks = listOf(track("B"))))
        }

        val feed = DashboardRepositoryImpl(registry, FakeEnabled(disabled = setOf("off"))).homeFeed()

        assertEquals(listOf("on"), feed.topTracks.map { it.providerId })
    }
}
