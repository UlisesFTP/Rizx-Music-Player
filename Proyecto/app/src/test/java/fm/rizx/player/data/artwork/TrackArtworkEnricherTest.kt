package fm.rizx.player.data.artwork

import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackArtworkEnricherTest {

    /**
     * Stands in for Deezer. Answers with the **same recording** it was asked about, because a
     * candidate that doesn't verify is no longer accepted — see [ArtworkOwnerFirstTest] for the
     * rejections.
     */
    private class CoverProvider(override val id: String = "deezer") : MetadataProvider {
        var searches = 0
        override val kind = ProviderKind.METADATA
        override val name = "Cover source"
        override val searchCapabilities = setOf(SearchCapability.UNIFIED)
        override suspend fun search(params: SearchParams): SearchResults {
            searches++
            return SearchResults(
                tracks = listOf(
                    Track(
                        title = "Song",
                        artists = listOf(ArtistCredit("Artist")),
                        artwork = ArtworkSet(listOf(Artwork(url = "https://deezer/cover.jpg"))),
                        source = ProviderRef("deezer", "1"),
                    ),
                ),
            )
        }
    }

    private fun enricher(provider: MetadataProvider) =
        TrackArtworkEnricher(DefaultProviderRegistry().apply { register(provider) }, io = Dispatchers.Unconfined)

    private fun track(provider: String, cover: String?, artist: String? = "Artist") = Track(
        title = "Song",
        artists = listOfNotNull(artist?.let { ArtistCredit(it) }),
        artwork = cover?.let { ArtworkSet(listOf(Artwork(url = it))) },
        source = ProviderRef(provider, "x"),
    )

    @Test
    fun `fills a missing cover and leaves an existing one alone`() = runTest {
        val provider = CoverProvider()
        val out = enricher(provider).enrich(listOf(track("spotify", null), track("deezer", "https://kept.jpg")))

        assertEquals("https://deezer/cover.jpg", out[0].artwork.coverUrl())
        assertEquals("https://kept.jpg", out[1].artwork.coverUrl())
        assertEquals(1, provider.searches) // the track that already had a cover cost nothing
    }

    @Test
    fun `upgradeFrom replaces a poor cover — a YouTube video still becomes the real artwork`() = runTest {
        val out = enricher(CoverProvider())
            .enrich(listOf(track("youtube", "https://i.ytimg.com/still.jpg")), upgradeFrom = setOf("youtube"))

        assertEquals("https://deezer/cover.jpg", out.single().artwork.coverUrl())
    }

    @Test
    fun `an upgrade that finds nothing keeps the original image rather than blanking the tile`() = runTest {
        val empty = object : MetadataProvider {
            override val id = "deezer"
            override val kind = ProviderKind.METADATA
            override val name = "Empty"
            override val searchCapabilities = setOf(SearchCapability.UNIFIED)
            override suspend fun search(params: SearchParams) = SearchResults()
        }

        val out = enricher(empty)
            .enrich(listOf(track("youtube", "https://i.ytimg.com/still.jpg")), upgradeFrom = setOf("youtube"))

        assertEquals("https://i.ytimg.com/still.jpg", out.single().artwork.coverUrl())
    }

    @Test
    fun `without upgradeFrom a YouTube track keeps its thumbnail and costs no lookup`() = runTest {
        val provider = CoverProvider()
        val out = enricher(provider).enrich(listOf(track("youtube", "https://i.ytimg.com/still.jpg")))

        assertEquals("https://i.ytimg.com/still.jpg", out.single().artwork.coverUrl())
        assertEquals(0, provider.searches)
    }

    @Test
    fun `a track with no artist is left alone instead of matched on its title`() = runTest {
        // The behaviour change this rewrite exists for. A bare title is not an identity: searching
        // "Intro" returned a stranger's song, and the answer was cached under "intro" for every
        // other track of that name — persisted to disk, surviving restarts.
        val provider = CoverProvider()
        val out = enricher(provider).enrich(listOf(track("import", cover = null, artist = null)))

        assertNull(out.single().artwork.coverUrl())
        assertEquals(0, provider.searches)
    }
}
