package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedDeduperTest {

    private val deduper = HomeFeedDeduper()

    private fun track(title: String, artist: String = "Artist", id: String = title) =
        Track(title = title, artists = listOf(ArtistCredit(name = artist)), source = ProviderRef("deezer", id))

    private fun album(title: String, artist: String = "Artist", id: String = title) = AlbumRef(
        title = title,
        artists = listOf(ArtistRef(name = artist, source = ProviderRef("deezer", "artist:$artist"))),
        source = ProviderRef("deezer", "album:$id"),
    )

    private fun artist(name: String, id: String = name) =
        ArtistRef(name = name, source = ProviderRef("deezer", "artist:$id"))

    private fun <T> chart(vararg items: T) = listOf(AttributedResult("blended", "Rizx", items.toList()))

    @Test
    fun `a song in two personalized rows is kept only by the first one`() {
        val shared = track("Shared")
        val sections = listOf(
            ForYouSection.Mix("Seed", listOf(shared, track("A"), track("B"))),
            ForYouSection.BecauseYouLike("Artist", listOf(shared, track("C"), track("D"), track("E"))),
        )

        val out = deduper.dedupe(HomeFeed(), sections)

        assertEquals(listOf("Shared", "A", "B"), (out.sections[0] as ForYouSection.Mix).items.map { it.title })
        assertEquals(
            listOf("C", "D", "E"),
            (out.sections[1] as ForYouSection.BecauseYouLike).items.map { it.title },
        )
    }

    @Test
    fun `a row left too short after filtering is dropped instead of shown as a stub`() {
        val a = track("A")
        val b = track("B")
        val sections = listOf(
            ForYouSection.Mix("First", listOf(a, b, track("C"))),
            ForYouSection.Mix("Second", listOf(a, b, track("D"))), // only "D" survives
        )

        val out = deduper.dedupe(HomeFeed(), sections)

        assertEquals(1, out.sections.size)
        assertEquals("First", (out.sections.single() as ForYouSection.Mix).seedTitle)
    }

    @Test
    fun `the charts drop what the personalized rows above already showed`() {
        val sections = listOf(
            ForYouSection.Mix("Seed", listOf(track("Chart hit"), track("A"), track("B"))),
            ForYouSection.ArtistsForYou(listOf(artist("Justice"), artist("Cassius"), artist("Etienne"))),
            ForYouSection.AlbumsForYou(listOf(album("Hyperdrama"), album("Woman"), album("Cross"))),
        )
        val feed = HomeFeed(
            topTracks = chart(track("Chart hit"), track("Another hit")),
            topArtists = chart(artist("Justice"), artist("Bad Bunny")),
            topAlbums = chart(album("Hyperdrama"), album("Nomada")),
        )

        val out = deduper.dedupe(feed, sections)

        assertEquals(listOf("Another hit"), out.feed.topTracks.single().items.map { it.title })
        assertEquals(listOf("Bad Bunny"), out.feed.topArtists.single().items.map { it.name })
        assertEquals(listOf("Nomada"), out.feed.topAlbums.single().items.map { it.title })
    }

    @Test
    fun `matching is normalized, so a feat tail or an accent doesn't sneak a second copy through`() {
        val sections = listOf(
            ForYouSection.Mix("Seed", listOf(track("Corazón (feat. X)", "José"), track("A"), track("B"))),
        )
        val feed = HomeFeed(topTracks = chart(track("Corazon", "Jose", id = "other"), track("Kept")))

        val out = deduper.dedupe(feed, sections)

        assertEquals(listOf("Kept"), out.feed.topTracks.single().items.map { it.title })
    }

    @Test
    fun `a remix is a different recording and survives the dedup`() {
        val sections = listOf(ForYouSection.Mix("Seed", listOf(track("Corazón"), track("A"), track("B"))))
        val feed = HomeFeed(topTracks = chart(track("Corazón (Remix)", id = "remix")))

        val out = deduper.dedupe(feed, sections)

        assertEquals(listOf("Corazón (Remix)"), out.feed.topTracks.single().items.map { it.title })
    }

    @Test
    fun `a chart emptied by the dedup disappears rather than becoming an empty row`() {
        val sections = listOf(ForYouSection.Mix("Seed", listOf(track("A"), track("B"), track("C"))))
        val feed = HomeFeed(topTracks = chart(track("A"), track("B")))

        val out = deduper.dedupe(feed, sections)

        assertTrue(out.feed.topTracks.isEmpty())
        assertTrue(out.feed.isEmpty)
    }

    @Test
    fun `albums and new releases share one shelf, so a charting release isn't listed twice`() {
        val feed = HomeFeed(topAlbums = chart(album("Nomada")), newReleases = chart(album("Nomada")))

        val out = deduper.dedupe(feed, emptyList())

        assertEquals(1, out.feed.topAlbums.single().items.size)
        assertTrue(out.feed.newReleases.isEmpty())
    }

    @Test
    fun `with feedFirst the charts keep every item and the arriving rows give way`() {
        // The Home draws the charts as soon as they land and fills the personalized rows in afterwards.
        // Re-running the normal direction then would yank songs out of a strip the user is looking at.
        val shared = track("Shared")
        val feed = HomeFeed(topTracks = chart(shared, track("X"), track("Y")))
        val sections = listOf(ForYouSection.Mix("Seed", listOf(shared, track("A"), track("B"), track("C"))))

        val out = deduper.dedupe(feed, sections, feedFirst = true)

        assertEquals(listOf("Shared", "X", "Y"), out.feed.topTracks.single().items.map { it.title })
        assertEquals(listOf("A", "B", "C"), (out.sections.single() as ForYouSection.Mix).items.map { it.title })
    }

    @Test
    fun `feedFirst still drops a row that falls under the minimum`() {
        val feed = HomeFeed(topTracks = chart(track("A"), track("B")))
        val sections = listOf(ForYouSection.Mix("Seed", listOf(track("A"), track("B"), track("C"))))

        val out = deduper.dedupe(feed, sections, feedFirst = true)

        assertEquals(2, out.feed.topTracks.single().items.size)
        assertTrue(out.sections.isEmpty())
    }

    @Test
    fun `nothing to dedup leaves the feed and the rows untouched`() {
        val sections = listOf(ForYouSection.Mix("Seed", listOf(track("A"), track("B"), track("C"))))
        val feed = HomeFeed(topTracks = chart(track("X"), track("Y")))

        val out = deduper.dedupe(feed, sections)

        assertEquals(1, out.sections.size)
        assertEquals(listOf("X", "Y"), out.feed.topTracks.single().items.map { it.title })
    }
}
