package fm.rizx.player.data.repository

import fm.rizx.player.data.local.settings.SettingsRepositoryImpl
import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.domain.model.DashboardCapability
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.DashboardProvider
import fm.rizx.player.domain.provider.EnabledProviderStore
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The feed-source selection: which providers the fan-out asks, and whose name ends up on the row.
 * Both halves matter — picking "Apple Music" and seeing rows labelled "Rizx" would be the blend
 * leaking through.
 */
class FeedSelectionTest {

    private class FakeDash(override val id: String, override val name: String, private val title: String) : DashboardProvider {
        override val kind = ProviderKind.DASHBOARD
        override val dashboardCapabilities = setOf(DashboardCapability.TOP_TRACKS)
        override suspend fun topTracks(limit: Int) = listOf(Track(title, source = ProviderRef(id, title)))
    }

    private class AllEnabled : EnabledProviderStore {
        override fun isEnabled(id: String): Flow<Boolean> = flowOf(true)
        override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun snapshot(ids: Collection<String>) = ids.associateWith { true }
    }

    private fun registry() = DefaultProviderRegistry().apply {
        register(FakeDash("deezer-dashboard", "Deezer Charts", "Deezer song"))
        register(FakeDash("applemusic-charts", "Apple Music", "Apple song"))
    }

    private fun repo(selection: String) =
        DashboardRepositoryImpl(registry(), AllEnabled(), selection = { selection })

    @Test
    fun `a pinned source is the only one asked`() = runBlocking {
        val feed = repo("applemusic-charts").homeFeed()

        assertEquals(listOf("applemusic-charts"), feed.topTracks.map { it.providerId })
        assertEquals(listOf("Apple song"), feed.topTracks.flatMap { r -> r.items.map { it.title } })
    }

    @Test
    fun `all combined asks every enabled source`() = runBlocking {
        val feed = repo(SettingsRepositoryImpl.FEED_PROVIDER_ALL).homeFeed()

        assertEquals(setOf("deezer-dashboard", "applemusic-charts"), feed.topTracks.map { it.providerId }.toSet())
    }

    @Test
    fun `a selection naming a provider that is gone falls back to the blend, not an empty Home`() = runBlocking {
        // The shape after uninstalling the dashboard plugin the feed was pinned to.
        val feed = repo("uninstalled-plugin-dashboard").homeFeed()

        assertEquals(2, feed.topTracks.size)
    }

    @Test
    fun `a single source keeps its own attribution instead of being relabelled Rizx`() = runBlocking {
        val blended = BlendingDashboardRepository(
            inner = repo("applemusic-charts"),
            blender = fm.rizx.player.domain.usecase.RecsBlender(),
            artwork = fm.rizx.player.data.artwork.TrackArtworkEnricher(DefaultProviderRegistry()),
        )

        val feed = blended.homeFeed()

        assertEquals(listOf("applemusic-charts"), feed.topTracks.map { it.providerId })
        assertEquals(listOf("Apple Music"), feed.topTracks.map { it.providerName })
    }

    @Test
    fun `two sources are blended under one synthesized attribution`() = runBlocking {
        val blended = BlendingDashboardRepository(
            inner = repo(SettingsRepositoryImpl.FEED_PROVIDER_ALL),
            blender = fm.rizx.player.domain.usecase.RecsBlender(),
            artwork = fm.rizx.player.data.artwork.TrackArtworkEnricher(DefaultProviderRegistry()),
        )

        val feed = blended.homeFeed()

        assertEquals(listOf(BlendingDashboardRepository.ID), feed.topTracks.map { it.providerId })
        assertEquals(2, feed.topTracks.single().items.size)
    }
}
