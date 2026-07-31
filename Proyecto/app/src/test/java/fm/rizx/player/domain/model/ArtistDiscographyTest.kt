package fm.rizx.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistDiscographyTest {

    @Test
    fun `the discography splits into records and singles, newest first`() {
        val artist = artist(
            release("Random Access Memories", 2013, AlbumKind.ALBUM),
            release("Instant Crush", 2013, AlbumKind.SINGLE),
            release("Discovery", 2001, AlbumKind.ALBUM),
            release("Alive 1997", 2001, AlbumKind.EP),
            release("Musique Vol. 1", 2006, AlbumKind.COMPILATION),
        )

        assertEquals(
            listOf("Random Access Memories", "Musique Vol. 1", "Discovery"),
            artist.albumsOnly.map { it.title },
        )
        assertEquals(listOf("Instant Crush", "Alive 1997"), artist.singlesAndEps.map { it.title })
    }

    @Test
    fun `a release the catalogue never labelled is shown with the albums, not lost`() {
        val artist = artist(release("Untitled", 2020, AlbumKind.UNKNOWN))

        assertEquals(listOf("Untitled"), artist.albumsOnly.map { it.title })
        assertTrue(artist.singlesAndEps.isEmpty())
    }

    @Test
    fun `a release with no year sorts last rather than dropping out`() {
        val artist = artist(
            release("No date", null, AlbumKind.ALBUM),
            release("2019 one", 2019, AlbumKind.ALBUM),
        )

        assertEquals(listOf("2019 one", "No date"), artist.albumsOnly.map { it.title })
    }

    @Test
    fun `catalogue wording maps onto the families, and anything else is unknown`() {
        assertEquals(AlbumKind.ALBUM, AlbumKind.of("album"))
        assertEquals(AlbumKind.SINGLE, AlbumKind.of("single"))
        assertEquals(AlbumKind.EP, AlbumKind.of("EP"))
        assertEquals(AlbumKind.COMPILATION, AlbumKind.of("compilation"))
        assertEquals(AlbumKind.UNKNOWN, AlbumKind.of("mixtape"))
        assertEquals(AlbumKind.UNKNOWN, AlbumKind.of(null))
    }

    private fun artist(vararg albums: AlbumRef) =
        Artist(name = "Daft Punk", albums = albums.toList(), source = ProviderRef("deezer", "artist:27"))

    private fun release(title: String, year: Int?, kind: AlbumKind) =
        AlbumRef(title = title, source = ProviderRef("deezer", "album:$title"), year = year, kind = kind)
}
