package fm.rizx.player.ui.home

import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speed dial's paging contract: the dice is always the last cell, and past one page only whole
 * 3×3 pages exist — a ragged trailing page would change the pager's height mid-swipe.
 */
class SpeedDialTest {

    private fun tracks(n: Int) = List(n) { Track(title = "Song $it", source = ProviderRef("deezer", "$it")) }

    private fun titles(page: List<SpeedCell>) = page.filterIsInstance<SpeedCell.Song>().map { it.track.title }

    @Test
    fun `seventeen songs make exactly two full pages, dice last`() {
        val pages = speedDialPages(tracks(17))

        assertEquals(2, pages.size)
        assertTrue(pages.all { it.size == 9 })
        assertEquals(SpeedCell.Dice, pages.last().last())
        assertEquals("Song 0", titles(pages.first()).first()) // newest-first order preserved
        assertEquals(17, pages.sumOf { page -> page.count { it is SpeedCell.Song } })
    }

    @Test
    fun `a history that can't fill page two is trimmed to one full page instead of a ragged second`() {
        val pages = speedDialPages(tracks(12))

        assertEquals(1, pages.size)
        assertEquals(9, pages.single().size)
        assertEquals(SpeedCell.Dice, pages.single().last())
        // The eight *newest* survive; the tail is what gets trimmed.
        assertEquals(List(8) { "Song $it" }, titles(pages.single()))
    }

    @Test
    fun `a thin history makes one short page — the only page is allowed to be short`() {
        val pages = speedDialPages(tracks(3))

        assertEquals(1, pages.size)
        assertEquals(4, pages.single().size)
        assertEquals(SpeedCell.Dice, pages.single().last())
    }

    @Test
    fun `no history, no grid`() {
        assertTrue(speedDialPages(emptyList()).isEmpty())
    }

    @Test
    fun `deeper histories keep making whole pages`() {
        val pages = speedDialPages(tracks(26))

        assertEquals(3, pages.size)
        assertTrue(pages.all { it.size == 9 })
        assertEquals(SpeedCell.Dice, pages.last().last())
    }
}
