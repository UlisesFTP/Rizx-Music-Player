package fm.rizx.player.data.repository

import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.domain.model.DashboardCapability
import fm.rizx.player.domain.model.MoodStation
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
        private val stations: List<MoodStation> = emptyList(),
        private val stationTracks: Map<String, List<Track>> = emptyMap(),
    ) : DashboardProvider {
        override val kind = ProviderKind.DASHBOARD
        override val name = id
        override val dashboardCapabilities = buildSet {
            add(DashboardCapability.TOP_TRACKS)
            if (stations.isNotEmpty()) add(DashboardCapability.MOOD_STATIONS)
        }
        override suspend fun topTracks(limit: Int): List<Track> =
            if (failTracks) throw RuntimeException("boom") else tracks
        override suspend fun moodStations(limit: Int): List<MoodStation> = stations.take(limit)
        override suspend fun stationTracks(stationId: String, limit: Int): List<Track> =
            stationTracks[stationId] ?: throw RuntimeException("unknown station")
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

    @Test
    fun `stations ride the fan-out attributed to the provider that can resolve them`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(FakeDash("d1", stations = listOf(MoodStation("31061", "Pop"))))
            register(FakeDash("d2")) // no MOOD_STATIONS capability → contributes none
        }

        val feed = DashboardRepositoryImpl(registry, FakeEnabled()).homeFeed()

        val result = feed.stations.single()
        assertEquals("d1", result.providerId)
        assertEquals(listOf("Pop"), result.items.map { it.title })
    }

    @Test
    fun `stationTracks routes to the named provider and degrades to empty otherwise`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(
                FakeDash(
                    "d1",
                    stations = listOf(MoodStation("31061", "Pop")),
                    stationTracks = mapOf("31061" to listOf(track("Abracadabra"))),
                ),
            )
        }
        val repo = DashboardRepositoryImpl(registry, FakeEnabled())

        assertEquals(listOf("Abracadabra"), repo.stationTracks("d1", "31061", 30).map { it.title })
        // Unknown provider → nobody can resolve it; a failing provider → same quiet empty.
        assertTrue(repo.stationTracks("nope", "31061", 30).isEmpty())
        assertTrue(repo.stationTracks("d1", "bad-id", 30).isEmpty())
    }
}
