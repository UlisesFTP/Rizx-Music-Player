package fm.rizx.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqPresetsTest {

    private val tenBand = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
        .mapIndexed { index, hz -> EqBandRange(index, hz, hz, hz) }

    @Test
    fun `levels returns one entry per band`() {
        EqPreset.entries.forEach { preset ->
            assertEquals(5, EqPresets.levels(preset, bandCount = 5, maxMillibel = 1500).size)
        }
    }

    @Test
    fun `flat is all zeros`() {
        assertTrue(EqPresets.levels(EqPreset.FLAT, 5, 1500).all { it == 0 })
    }

    @Test
    fun `bass favors low frequencies over high frequencies`() {
        val levels = levels(EqPreset.BASS)
        assertTrue("low band favored", levels[1] > levels[8])
    }

    @Test
    fun `treble favors high frequencies over low frequencies`() {
        val levels = levels(EqPreset.TREBLE)
        assertTrue("high band favored", levels[8] > levels[1])
    }

    @Test
    fun `every preset respects both device limits`() {
        EqPreset.entries.forEach { preset ->
            assertTrue(levels(preset, min = -700, max = 350).all { it in -700..350 })
        }
    }

    @Test
    fun `boost is capped at four decibels even when device allows more`() {
        EqPreset.entries.forEach { preset ->
            assertTrue(levels(preset).all { it <= 400 })
        }
    }

    @Test
    fun `vocal and podcast favor presence over rumble`() {
        listOf(EqPreset.VOCAL, EqPreset.PODCAST).forEach { preset ->
            val levels = levels(preset)
            assertTrue(levels[6] > levels[1])
        }
    }

    @Test
    fun `real band ranges are averaged instead of using ordinal position`() {
        val wideBass = listOf(EqBandRange(0, 125, 31, 500), EqBandRange(1, 4_000, 1_000, 16_000))
        val result = EqPresets.levels(EqPreset.BASS, wideBass, -1_500, 1_500)
        assertTrue(result.first() > result.last())
    }

    @Test
    fun `zero bands yields empty`() {
        assertTrue(EqPresets.levels(EqPreset.BASS, bandCount = 0, maxMillibel = 1500).isEmpty())
    }

    private fun levels(preset: EqPreset, min: Int = -1_500, max: Int = 1_500): List<Int> =
        EqPresets.levels(preset, tenBand, min, max)
}
