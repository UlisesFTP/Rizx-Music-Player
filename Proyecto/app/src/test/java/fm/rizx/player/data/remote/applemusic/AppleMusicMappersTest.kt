package fm.rizx.player.data.remote.applemusic

import fm.rizx.player.data.remote.itunes.ItunesResultDto
import fm.rizx.player.domain.model.ProviderRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apple Music's mapping over the iTunes Search rows. The DTO is shared with the preview-only iTunes
 * provider, so the identity namespace is what these guard: two providers writing the same
 * `ProviderRef` would silently merge two different catalogues.
 */
class AppleMusicMappersTest {

    private fun song(
        trackId: Long? = 1234L,
        artistId: Long? = 479756766L,
        collectionId: Long? = 1499378108L,
    ) = ItunesResultDto(
        wrapperType = "track",
        trackId = trackId,
        artistId = artistId,
        collectionId = collectionId,
        trackName = "Blinding Lights",
        artistName = "The Weeknd",
        collectionName = "After Hours",
        artworkUrl100 = "https://is1.mzstatic.com/x/100x100bb.jpg",
        trackTimeMillis = 200_040,
        trackNumber = 9,
        primaryGenreName = "R&B/Soul",
    )

    @Test
    fun `a song maps with the applemusic namespace, not itunes`() {
        val track = song().toAppleTrackOrNull()!!

        assertEquals(AppleMusicIds.PROVIDER, track.source.provider)
        assertEquals("1234", track.source.id)
        assertEquals("artist:479756766", track.artists.single().source?.id)
        assertEquals("album:1499378108", track.album?.source?.id)
        assertEquals(200_040L, track.durationMs)
        assertEquals(listOf("R&B/Soul"), track.tags)
    }

    @Test
    fun `the 100px thumbnail is upsized to a real cover, keeping the small one as a variant`() {
        val artwork = song().toAppleTrackOrNull()!!.artwork!!

        assertTrue(artwork.items.first().url.contains("600x600bb"))
        assertEquals(2, artwork.items.size)
    }

    @Test
    fun `a row with no track id is dropped rather than given a made-up identity`() {
        assertNull(song(trackId = null).toAppleTrackOrNull())
    }

    @Test
    fun `an album lookup becomes full detail, ignoring the leading album row`() {
        val rows = listOf(
            ItunesResultDto(
                wrapperType = "collection",
                collectionId = 1499378108L,
                artistId = 479756766L,
                collectionName = "After Hours",
                artistName = "The Weeknd",
                artworkUrl100 = "https://is1.mzstatic.com/x/100x100bb.jpg",
                trackCount = 14,
                releaseDate = "2020-03-20T07:00:00Z",
            ),
            song(trackId = 1L),
            song(trackId = 2L),
        )

        val album = rows.toAppleAlbum(AppleMusicIds.albumRef(1499378108L))!!

        assertEquals("After Hours", album.title)
        assertEquals(2020, album.year)
        assertEquals("2020-03-20", album.releaseDateIso)
        assertEquals(14, album.totalTracks)
        assertEquals(2, album.tracks.size) // the album row itself is not a track
    }

    @Test
    fun `artist detail merges the top-songs and albums lookups`() {
        val albums = listOf(
            ItunesResultDto(
                wrapperType = "collection",
                collectionId = 1L,
                collectionName = "Dawn FM",
                artistName = "The Weeknd",
                artistId = 479756766L,
            ),
        )

        val artist = appleArtistDetail(AppleMusicIds.artistRef(479756766L), listOf(song()), albums)!!

        assertEquals("The Weeknd", artist.name)
        assertEquals(listOf("Blinding Lights"), artist.topTracks.map { it.title })
        assertEquals(listOf("Dawn FM"), artist.albums.map { it.title })
        assertNotNull(artist.artwork) // borrowed from the first top track
    }

    @Test
    fun `artist detail with nothing in it is null, not an empty artist page`() {
        assertNull(appleArtistDetail(AppleMusicIds.artistRef(1L), emptyList(), emptyList()))
    }

    @Test
    fun `unified search derives distinct artists and albums from the song rows`() {
        val results = listOf(song(trackId = 1L), song(trackId = 2L)).toAppleSearchResults()

        assertEquals(2, results.tracks.size)
        assertEquals(1, results.artists.size) // same artist twice → one ref
        assertEquals(1, results.albums.size)
    }

    @Test
    fun `idOf only unwraps its own refs and only the asked-for kind`() {
        assertEquals("42", AppleMusicIds.idOf(AppleMusicIds.artistRef(42L), "artist"))
        assertNull(AppleMusicIds.idOf(AppleMusicIds.artistRef(42L), "album"))
        assertNull(AppleMusicIds.idOf(ProviderRef("deezer", "artist:42"), "artist"))
    }
}
