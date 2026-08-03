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

    @Test
    fun `single-source stations and featured cards pass through with their real attribution`() = runTest {
        // The point of the passthrough: with one provider supplying them (Deezer today), the blend
        // must not rename its contribution "Rizx" nor lose the providerId the station tap routes by.
        val station = fm.rizx.player.domain.model.MoodStation("37121", "Chill Out")
        val card = fm.rizx.player.domain.model.FeaturedPlaylist(
            playlist = fm.rizx.player.domain.model.PlaylistRef(
                id = "1", name = "Top", source = ProviderRef("deezer", "playlist:1"),
            ),
            preview = listOf(track("Peek", "deezer", "5")),
        )
        val inner = object : DashboardRepository {
            override suspend fun homeFeed() = HomeFeed(
                stations = listOf(AttributedResult("deezer-dashboard", "Deezer", listOf(station))),
                featured = listOf(AttributedResult("deezer-dashboard", "Deezer", listOf(card))),
            )
        }

        val feed = repo(inner).homeFeed()

        assertEquals("deezer-dashboard", feed.stations.single().providerId)
        assertEquals(listOf("Chill Out"), feed.stations.single().items.map { it.title })
        assertEquals("deezer-dashboard", feed.featured.single().providerId)
        assertEquals("Top", feed.featured.single().items.single().playlist.name)
    }

    @Test
    fun `stationTracks is forwarded to the inner repository untouched`() = runTest {
        val inner = object : DashboardRepository {
            override suspend fun homeFeed() = HomeFeed()
            override suspend fun stationTracks(providerId: String, stationId: String, limit: Int) =
                listOf(track("From $providerId/$stationId", "deezer", "9"))
        }

        val tracks = repo(inner).stationTracks("deezer-dashboard", "37121", 30)

        assertEquals(listOf("From deezer-dashboard/37121"), tracks.map { it.title })
    }
}
