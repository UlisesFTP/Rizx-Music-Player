package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.PlayStat
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ContinueListeningTest {

    private val now = Instant.parse("2026-07-30T12:00:00Z").toEpochMilli()

    @Test
    fun `the row opens on the last thing played`() {
        val row = ContinueListening.build(profile(fresh(15)))

        assertEquals("Fresh 0", row.first().title)
    }

    @Test
    fun `something you loved months ago comes back`() {
        val stats = fresh(15) +
            stat("Old favourite", at = "2026-05-01T12:00:00Z", plays = 6) +
            stat("Tried once", at = "2026-05-01T12:00:00Z", plays = 1)

        val row = ContinueListening.build(profile(stats))

        assertTrue("a song replayed six times and not heard since May belongs here", row.any { it.title == "Old favourite" })
        assertFalse("something tried once and dropped is not a memory", row.any { it.title == "Tried once" })
    }

    @Test
    fun `what you keep replaying is in there too`() {
        val stats = fresh(15) + stat("On repeat", at = "2026-07-29T12:00:00Z", plays = 12)

        val row = ContinueListening.build(profile(stats))

        assertTrue(row.any { it.title == "On repeat" })
    }

    @Test
    fun `nothing unplayed may appear, however much you like it`() {
        val liked = List(5) { track("Liked $it") }

        val row = ContinueListening.build(
            TasteProfile(fresh(15), liked = liked, nowMs = now),
        )

        assertTrue("the row's title is a promise about where these songs come from", row.none { it.title.startsWith("Liked") })
    }

    @Test
    fun `the row never repeats a song, even when it qualifies twice`() {
        // Old *and* heavily replayed: it belongs to two pools, and may still take only one slot.
        val stats = fresh(15) + stat("Both", at = "2026-04-01T12:00:00Z", plays = 30)

        val row = ContinueListening.build(profile(stats))

        assertEquals(row.distinctBy { it.source }.size, row.size)
    }

    @Test
    fun `a thin history is simply the last songs played`() {
        val stats = fresh(3)

        val row = ContinueListening.build(profile(stats))

        assertEquals(listOf("Fresh 0", "Fresh 1", "Fresh 2"), row.map { it.title })
    }

    @Test
    fun `an empty log draws nothing at all`() {
        assertTrue(ContinueListening.build(profile(emptyList())).isEmpty())
    }

    @Test
    fun `the row is full whenever the log can fill it`() {
        assertEquals(ContinueListening.SLOTS, ContinueListening.build(profile(fresh(40))).size)
    }

    @Test
    fun `the same day always gives the same row, and another day varies it`() {
        val stats = fresh(6) +
            List(8) { stat("Old $it", at = "2026-04-0${it + 1}T12:00:00Z", plays = 4) }

        val monday = ContinueListening.build(profile(stats), dayEpoch = 1)
        val alsoMonday = ContinueListening.build(profile(stats), dayEpoch = 1)
        val friday = ContinueListening.build(profile(stats), dayEpoch = 5)

        assertEquals("the row must not reshuffle under a thumb", monday, alsoMonday)
        assertTrue("a new day should bring different old songs", monday != friday)
        assertEquals("but it still opens on what you were playing", monday.first(), friday.first())
    }

    // ---- Helpers --------------------------------------------------------------------------------

    /** [count] songs played over the last few hours, newest first — an ordinary recent history. */
    private fun fresh(count: Int) = List(count) {
        stat("Fresh $it", at = "2026-07-30T${(11 - it % 10).toString().padStart(2, '0')}:00:00Z")
    }

    private fun profile(stats: List<PlayStat>) = TasteProfile(stats, nowMs = now)

    private fun stat(title: String, at: String, plays: Int = 1) = PlayStat(
        track = track(title),
        plays = plays,
        lastPlayedAtIso = at,
    )

    private fun track(title: String) =
        Track(title, artists = listOf(ArtistCredit("Someone $title")), source = ProviderRef("deezer", title))
}
