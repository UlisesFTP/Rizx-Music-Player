package fm.rizx.player.ui.home

import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPickTest {

    private fun tracks(n: Int) = List(n) { Track(title = "Song $it", source = ProviderRef("deezer", "$it")) }

    @Test
    fun `quick picks preserve order and stop at six`() {
        assertEquals(List(6) { "Song $it" }, quickPicks(tracks(12)).map { it.title })
    }

    @Test
    fun `provider identity deduplicates even when metadata differs`() {
        val first = Track(title = "First", source = ProviderRef("deezer", "7", "https://one"))
        val duplicate = Track(title = "Renamed", source = ProviderRef("deezer", "7", "https://two"))
        val otherProvider = Track(title = "Other", source = ProviderRef("itunes", "7"))

        assertEquals(listOf(first, otherProvider), quickPicks(listOf(first, duplicate, otherProvider)))
    }

    @Test
    fun `thin histories have no placeholder cells`() {
        assertEquals(3, quickPicks(tracks(3)).size)
        assertTrue(quickPicks(emptyList()).isEmpty())
        assertTrue(quickPicks(tracks(3), limit = 0).isEmpty())
    }
}
