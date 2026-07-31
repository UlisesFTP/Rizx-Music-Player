package fm.rizx.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayOutcomeTest {

    private val threeMinutes = 180_000L

    @Test
    fun `a track that ran to the end is a track heard`() {
        assertEquals(PlayOutcome.COMPLETED, PlayOutcome.of(threeMinutes, threeMinutes, manual = false))
    }

    @Test
    fun `leaving in the first third is a skip`() {
        assertEquals(PlayOutcome.SKIPPED, PlayOutcome.of(20_000, threeMinutes, manual = true))
    }

    @Test
    fun `pressing next over the outro is not a rejection`() {
        // People listen this way. Counting it against the song would slowly bury their favourites.
        assertEquals(PlayOutcome.COMPLETED, PlayOutcome.of(170_000, threeMinutes, manual = true))
    }

    @Test
    fun `an unknown length says nothing either way`() {
        assertNull(PlayOutcome.of(5_000, durationMs = null, manual = true))
        assertNull(PlayOutcome.of(5_000, durationMs = 0, manual = true))
    }

    @Test
    fun `a stat with no outcome recorded reads as neutral, not as a failure`() {
        val fresh = PlayStat(Track("Yellow", source = ProviderRef("deezer", "1")), plays = 3)

        assertEquals(PlayStat.NEUTRAL_COMPLETION, fresh.completionRate, 0.001f)
        assertEquals(0f, fresh.skipRate, 0.001f)
    }

    @Test
    fun `dayparts split the clock into four`() {
        assertEquals(Daypart.NIGHT, Daypart.ofHour(2))
        assertEquals(Daypart.MORNING, Daypart.ofHour(9))
        assertEquals(Daypart.AFTERNOON, Daypart.ofHour(15))
        assertEquals(Daypart.EVENING, Daypart.ofHour(23))
    }
}
