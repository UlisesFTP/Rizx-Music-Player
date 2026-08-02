package fm.rizx.player.data.remote.deezer

import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkTargetPx
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.domain.model.heroUrl
import fm.rizx.player.domain.model.pick
import fm.rizx.player.domain.model.thumbnailUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which file the app actually downloads for a cover — the largest non-audio cost it has.
 *
 * The 500px variant Deezer publishes was never mapped, and because `pick()` only considers variants
 * whose purpose matches, the 1000px XL was the sole `COVER` candidate: every tile in a 60-item feed
 * fetched one. Measured against Deezer, the same cover is 63-212 KB at 1000px and 22-87 KB at 500px.
 *
 * So these assert the *choice*, not the mapping — a test that only checked the URLs were present would
 * have passed all along while the app kept downloading four times what it needed.
 */
class DeezerArtworkSizeTest {

    private fun album(
        xl: String? = "https://cdn/xl.jpg",
        big: String? = "https://cdn/big.jpg",
        medium: String? = "https://cdn/medium.jpg",
    ) = DeezerAlbumShortDto(id = 1, title = "After Hours", coverXl = xl, coverBig = big, coverMedium = medium)

    @Test
    fun `a cover defaults to the largest rung the provider publishes`() {
        // Owner's decision: covers are the app's face, so quality is the default and Data saver is
        // what steps down. The 500 stays reachable by asking for it — see the target-size case below.
        val artwork = album().toAlbumRef()!!.artwork

        assertEquals("https://cdn/xl.jpg", artwork.coverUrl())
    }

    @Test
    fun `a full-screen hero still takes the 1000px one`() {
        // Now Playing fills the screen with this; shrinking it would save nothing worth the quality.
        val artwork = album().toAlbumRef()!!.artwork

        assertEquals("https://cdn/xl.jpg", artwork.heroUrl())
    }

    @Test
    fun `data saving drops to the 250px thumbnail`() {
        val artwork = album().toAlbumRef()!!.artwork

        assertEquals("https://cdn/medium.jpg", artwork.thumbnailUrl())
    }

    @Test
    fun `an entry with no 500px variant still answers with something`() {
        // Not every row Deezer returns carries every size, and a missing rung must degrade rather than
        // leave a blank tile.
        val artwork = album(big = null).toAlbumRef()!!.artwork

        assertEquals("https://cdn/xl.jpg", artwork.coverUrl())
        assertEquals("https://cdn/xl.jpg", artwork.heroUrl())
    }

    @Test
    fun `an entry with only a thumbnail falls back to it rather than to nothing`() {
        val artwork = album(xl = null, big = null).toAlbumRef()!!.artwork

        assertEquals("https://cdn/medium.jpg", artwork.thumbnailUrl())
    }

    @Test
    fun `all three sizes are mapped, each labelled for what it is good for`() {
        val artwork = album().toAlbumRef()!!.artwork!!

        assertEquals(3, artwork.items.size)
        assertEquals(2, artwork.items.count { it.purpose == ArtworkPurpose.COVER })
        assertEquals(1, artwork.items.count { it.purpose == ArtworkPurpose.THUMBNAIL })
    }

    @Test
    fun `the requested size is what chooses, so both covers stay reachable`() {
        // The mechanism the fix rests on: two COVER variants and a target px, rather than one variant
        // and no choice at all.
        val artwork = album().toAlbumRef()!!.artwork

        assertEquals("https://cdn/big.jpg", artwork.pick(ArtworkPurpose.COVER, ArtworkTargetPx.COVER)?.url)
        assertEquals("https://cdn/xl.jpg", artwork.pick(ArtworkPurpose.COVER, ArtworkTargetPx.BACKGROUND)?.url)
    }

    @Test
    fun `an entry with no artwork at all stays null`() {
        assertNull(album(xl = null, big = null, medium = null).toAlbumRef()!!.artwork)
    }

    @Test
    fun `a track inherits its album's sizing`() {
        val track = DeezerTrackDto(
            id = 7,
            title = "Save Your Tears",
            album = album(),
        ).toTrackOrNull()!!

        assertEquals("https://cdn/xl.jpg", track.artwork.coverUrl())
        assertEquals("https://cdn/xl.jpg", track.artwork.heroUrl())
    }
}
