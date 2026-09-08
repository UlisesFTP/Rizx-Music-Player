package fm.rizx.player.ui.util

import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryStatsTest {

    private fun track(title: String, durationMs: Long?, vararg artists: String) = Track(
        title = title,
        artists = artists.map { ArtistCredit(it) },
        durationMs = durationMs,
        source = ProviderRef("deezer", title),
    )

    @Test
    fun `running time reads like the design`() {
        assertEquals("18 min", LibraryStats.formatLongDuration(18 * 60_000L))
        assertEquals("3 h 24 min", LibraryStats.formatLongDuration((3 * 60 + 24) * 60_000L))
        // Minutes under ten keep their leading zero, as in `44 h 07 min`.
        assertEquals("44 h 07 min", LibraryStats.formatLongDuration((44 * 60 + 7) * 60_000L))
        assertEquals("0 min", LibraryStats.formatLongDuration(0L))
        // Rounds to the nearest minute rather than truncating.
        assertEquals("1 min", LibraryStats.formatLongDuration(40_000L))
    }

    @Test
    fun `the clock pads seconds and admits it does not know`() {
        assertEquals("3:07", LibraryStats.clock(187_000L))
        assertEquals("0:00", LibraryStats.clock(0L))
        assertEquals("61:05", LibraryStats.clock(3_665_000L))
        assertEquals("–:––", LibraryStats.clock(null))
    }

    @Test
    fun `serials are two digits`() {
        assertEquals("01", LibraryStats.padded(1))
        assertEquals("12", LibraryStats.padded(12))
        assertEquals("229", LibraryStats.padded(229))
    }

    @Test
    fun `totals skip unknown durations instead of failing`() {
        val tracks = listOf(track("A", 60_000L), track("B", null), track("C", 30_000L))
        assertEquals(90_000L, LibraryStats.totalMs(tracks))
    }

    @Test
    fun `artists are counted once regardless of case`() {
        val tracks = listOf(
            track("A", 1L, "Rosalía", "Bad Bunny"),
            track("B", 1L, "ROSALÍA"),
            track("C", 1L, " bad bunny "),
            track("D", 1L, ""),
        )
        assertEquals(2, LibraryStats.distinctArtists(tracks))
    }

    @Test
    fun `a collage cycles a short list and leaves an empty one empty`() {
        assertEquals(emptyList<String>(), LibraryStats.collage(emptyList<String>()))
        assertEquals(listOf("a", "a", "a", "a"), LibraryStats.collage(listOf("a")))
        assertEquals(listOf("a", "b", "c", "a"), LibraryStats.collage(listOf("a", "b", "c")))
        assertEquals(listOf("a", "b", "c", "d"), LibraryStats.collage(listOf("a", "b", "c", "d", "e")))
    }
}
