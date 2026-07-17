package fm.rizx.player.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumArtistTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `album round-trips through serialization keeping its track list`() {
        val album = Album(
            title = "Discovery",
            artists = listOf(ArtistRef("Daft Punk", source = ProviderRef("deezer", "artist:27"))),
            year = 2001,
            tracks = listOf(Track(title = "One More Time", source = ProviderRef("deezer", "10"))),
            totalTracks = 14,
            source = ProviderRef("deezer", "album:302127"),
        )

        val decoded = json.decodeFromString(Album.serializer(), json.encodeToString(Album.serializer(), album))

        assertEquals(album, decoded)
        assertEquals("One More Time", decoded.tracks.single().title)
        assertEquals("album:302127", decoded.source.id) // identity is the ProviderRef
    }

    @Test
    fun `artist round-trips keeping top tracks and album refs`() {
        val artist = Artist(
            name = "Daft Punk",
            followers = 9_600_000,
            topTracks = listOf(Track(title = "Aerodynamic", source = ProviderRef("deezer", "11"))),
            albums = listOf(AlbumRef("Discovery", source = ProviderRef("deezer", "album:302127"))),
            source = ProviderRef("deezer", "artist:27"),
        )

        val decoded = json.decodeFromString(Artist.serializer(), json.encodeToString(Artist.serializer(), artist))

        assertEquals(artist, decoded)
        assertEquals(9_600_000L, decoded.followers)
    }
}
