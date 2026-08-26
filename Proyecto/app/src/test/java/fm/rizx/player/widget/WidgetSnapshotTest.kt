package fm.rizx.player.widget

import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSnapshotTest {

    private val track = Track(
        title = "Hurt",
        artists = listOf(ArtistCredit("Nine Inch Nails"), ArtistCredit("Trent Reznor")),
        durationMs = 372_000L,
        artwork = ArtworkSet(listOf(Artwork("https://img/cover.jpg", 500, 500, ArtworkPurpose.COVER))),
        source = ProviderRef("deezer", "1"),
    )

    @Test
    fun `a track becomes the widget's view of it`() {
        val snapshot = WidgetSnapshot.of(track, isPlaying = true, liked = true, positionMs = 93_000L, durationMs = null)

        assertEquals("Hurt", snapshot.title)
        assertEquals("Nine Inch Nails, Trent Reznor", snapshot.artist)
        assertEquals("https://img/cover.jpg", snapshot.artworkUrl)
        assertEquals("deezer:1", snapshot.identityKey)
        assertTrue(snapshot.isPlaying)
        assertTrue(snapshot.liked)
        assertEquals("the track's own length when the player has none yet", 372_000L, snapshot.durationMs)
        assertEquals(0.25f, snapshot.progress, 0.001f)
        assertTrue(snapshot.hasTrack)
    }

    @Test
    fun `the player's duration wins over the catalogue's, and a negative position is clamped`() {
        val snapshot = WidgetSnapshot.of(track, isPlaying = false, liked = false, positionMs = -5L, durationMs = 400_000L)

        assertEquals(400_000L, snapshot.durationMs)
        assertEquals(0L, snapshot.positionMs)
        assertEquals(0f, snapshot.progress, 0f)
    }

    @Test
    fun `no track is the empty snapshot`() {
        val snapshot = WidgetSnapshot.of(null, isPlaying = true, liked = true, positionMs = 10L, durationMs = 20L)

        assertEquals(WidgetSnapshot.EMPTY, snapshot)
        assertFalse(snapshot.hasTrack)
        assertNull(snapshot.artist)
        assertEquals("no duration, no progress", 0f, snapshot.progress, 0f)
    }

    @Test
    fun `a nameless artist list reads as no artist`() {
        val snapshot = WidgetSnapshot.of(track.copy(artists = emptyList()), false, false, 0L, null)

        assertNull(snapshot.artist)
    }

    @Test
    fun `the clock reads like a player's`() {
        assertEquals("0:00", formatClock(0L))
        assertEquals("0:00", formatClock(-1_000L))
        assertEquals("1:05", formatClock(65_000L))
        assertEquals("59:59", formatClock(3_599_999L))
        assertEquals("1:00:00", formatClock(3_600_000L))
        assertEquals("1:01:01", formatClock(3_661_000L))
    }
}
