package fm.rizx.player.ui.home

import fm.rizx.player.domain.model.AppMix
import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.FeaturedPlaylist
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.MixKind
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePresentationTest {

    private fun track(provider: String, id: String) =
        Track(title = "$provider-$id", source = ProviderRef(provider, id))

    @Test
    fun `daypart boundaries are deterministic`() {
        assertEquals(HomeMoment.EVENING, homeMoment(4))
        assertEquals(HomeMoment.MORNING, homeMoment(5))
        assertEquals(HomeMoment.MORNING, homeMoment(11))
        assertEquals(HomeMoment.AFTERNOON, homeMoment(12))
        assertEquals(HomeMoment.AFTERNOON, homeMoment(18))
        assertEquals(HomeMoment.EVENING, homeMoment(19))
    }

    @Test
    fun `hero selection is mix then featured then recommendation`() {
        val mix = AppMix(kind = MixKind.DAILY, tracks = listOf(track("rizx", "mix")))
        val featured = FeaturedPlaylist(
            playlist = PlaylistRef("p", "Editorial", source = ProviderRef("spotify", "p")),
            preview = listOf(track("spotify", "preview")),
        )
        val recommendation = ForYouSection.Mix("Seed", listOf(track("youtube", "rec")))
        val feed = HomeFeed(
            featured = listOf(AttributedResult("spotify", "Spotify", listOf(featured))),
        )

        val result = homeHeroItems(listOf(mix), feed, listOf(recommendation))

        assertEquals(3, result.size)
        assertTrue(result[0] is HomeHeroItem.Mix)
        assertTrue(result[1] is HomeHeroItem.Featured)
        assertTrue(result[2] is HomeHeroItem.Recommendation)
    }

    @Test
    fun `duplicate anchors and non playable similar rows are skipped`() {
        val anchor = track("youtube", "same")
        val mix = AppMix(kind = MixKind.DAILY, tracks = listOf(anchor))
        val featured = FeaturedPlaylist(
            playlist = PlaylistRef("p", "Editorial", source = ProviderRef("spotify", "p")),
            preview = listOf(anchor.copy(title = "Duplicate metadata")),
        )
        val feed = HomeFeed(
            featured = listOf(AttributedResult("spotify", "Spotify", listOf(featured))),
        )
        val similar = ForYouSection.SimilarTo(anchorName = "Artist")

        assertEquals(listOf(HomeHeroItem.Mix(mix)), homeHeroItems(listOf(mix), feed, listOf(similar)))
    }

    @Test
    fun `hero limit is honored`() {
        val mix = AppMix(kind = MixKind.GLOBAL, tracks = listOf(track("rizx", "mix")))
        assertEquals(1, homeHeroItems(listOf(mix), HomeFeed(), emptyList(), limit = 1).size)
        assertTrue(homeHeroItems(listOf(mix), HomeFeed(), emptyList(), limit = 0).isEmpty())
    }
}
