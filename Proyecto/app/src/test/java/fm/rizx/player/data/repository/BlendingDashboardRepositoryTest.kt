package fm.rizx.player.data.repository

import fm.rizx.player.data.artwork.TrackArtworkEnricher
import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.DashboardRepository
import fm.rizx.player.domain.usecase.RecsBlender
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlendingDashboardRepositoryTest {

    private fun track(title: String, provider: String, id: String) = Track(
        title = title,
        artists = listOf(ArtistCredit(name = "Artist")),
        source = ProviderRef(provider, id),
    )

    // No metadata providers registered → the enricher returns tracks untouched (a no-op here).
    private fun repo(inner: DashboardRepository) =
        BlendingDashboardRepository(inner, RecsBlender(), TrackArtworkEnricher(DefaultProviderRegistry()))

    @Test
    fun `each section collapses to one synthesized attributed result carrying the blend`() = runTest {
        val inner = object : DashboardRepository {
            override suspend fun homeFeed() = HomeFeed(
                topTracks = listOf(
                    AttributedResult(
                        "applemusic-charts", "Apple Music",
                        listOf(track("Song A", "applemusic", "9"), track("Song B", "applemusic", "8")),
                    ),
                    AttributedResult("deezer-dashboard", "Deezer", listOf(track("Song A", "deezer", "1"))),
                ),
            )
        }

        val feed = repo(inner).homeFeed()

        assertEquals(1, feed.topTracks.size)
        assertEquals(BlendingDashboardRepository.ID, feed.topTracks.single().providerId)
        val tracks = feed.topTracks.single().items
        assertEquals(2, tracks.size) // "Song A" deduped across sources
        assertEquals("deezer", tracks.first { it.title == "Song A" }.source.provider)
    }

    @Test
    fun `empty sections stay absent instead of becoming empty attributed results`() = runTest {
        val inner = object : DashboardRepository {
            override suspend fun homeFeed() = HomeFeed()
        }

        val feed = repo(inner).homeFeed()

        assertTrue(feed.isEmpty)
        assertTrue(feed.topTracks.isEmpty())
        assertTrue(feed.editorialPlaylists.isEmpty())
    }
}
