package fm.rizx.player.domain.discovery

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SoundGenre
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.usecase.ArtistNameMatching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryFilterTest {

    private fun track(title: String, artist: String) =
        Track(title, artists = listOf(ArtistCredit(artist)), source = ProviderRef("deezer", title))

    private fun album(title: String, artist: String) =
        AlbumRef(title, artists = listOf(ArtistRef(artist, source = ProviderRef("deezer", "artist:$artist"))), source = ProviderRef("deezer", "album:$title"))

    private fun pl(id: String) = PlaylistRef(id = id, name = id, source = ProviderRef("deezer", "playlist:$id"))

    private val hiddenArtist = HiddenItems(artistKeys = setOf(ArtistNameMatching.key("Bad Bunny")))

    @Test
    fun `nothing hidden returns the very same Home`() {
        val feed = HomeFeed(topTracks = listOf(AttributedResult("d", "Deezer", listOf(track("A", "X")))))
        val sections = listOf(ForYouSection.Mix("A", listOf(track("A", "X"))))

        val out = DiscoveryFilter.apply(feed, sections, HiddenItems())

        assertSame(feed, out.feed)
        assertSame(sections, out.sections)
    }

    @Test
    fun `a hidden artist leaves every shelf, whatever the spelling`() {
        val feed = HomeFeed(
            topTracks = listOf(AttributedResult("d", "Deezer", listOf(track("Tití", "BAD BUNNY"), track("Other", "Karol G")))),
            topArtists = listOf(AttributedResult("d", "Deezer", listOf(ArtistRef("Bad Bunny", source = ProviderRef("deezer", "artist:1"))))),
            newReleases = listOf(AttributedResult("d", "Deezer", listOf(album("DtMF", "Bad Bunny")))),
        )

        val out = DiscoveryFilter.apply(feed, emptyList(), hiddenArtist)

        assertEquals(listOf("Other"), out.feed.topTracks.single().items.map { it.title })
        assertTrue("an emptied section disappears", out.feed.topArtists.isEmpty())
        assertTrue(out.feed.newReleases.isEmpty())
    }

    @Test
    fun `rows built around a hidden artist go, rows that merely mention them are thinned`() {
        val sections = listOf(
            ForYouSection.PlaylistsWith("Bad Bunny", listOf(pl("1"), pl("2"), pl("3"))),
            ForYouSection.BecauseYouLike("Bad Bunny", listOf(track("x", "Y"))),
            ForYouSection.Mix("seed", listOf(track("a", "Bad Bunny"), track("b", "Rauw"))),
            ForYouSection.NewFromArtists(listOf(album("DtMF", "Bad Bunny"))),
        )

        val out = DiscoveryFilter.apply(HomeFeed(), sections, hiddenArtist).sections

        assertEquals(1, out.size)
        assertEquals(listOf("b"), (out.single() as ForYouSection.Mix).items.map { it.title })
    }

    @Test
    fun `a hidden playlist leaves the charts and the discovery shelves`() {
        val hidden = HiddenItems(playlistKeys = setOf(pl("2").source.identityKey))
        val feed = HomeFeed(editorialPlaylists = listOf(AttributedResult("d", "Deezer", listOf(pl("1"), pl("2")))))
        val sections = listOf(ForYouSection.GenrePlaylists(SoundGenre.POP, listOf(pl("2"), pl("3"), pl("4"))))

        val out = DiscoveryFilter.apply(feed, sections, hidden)

        assertEquals(listOf("1"), out.feed.editorialPlaylists.single().items.map { it.id })
        assertEquals(listOf("3", "4"), (out.sections.single() as ForYouSection.GenrePlaylists).playlists.map { it.id })
    }
}
