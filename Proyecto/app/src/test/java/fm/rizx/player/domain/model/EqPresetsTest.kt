package fm.rizx.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqPresetsTest {

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
    fun `bass boosts the low bands and leaves the high bands flat`() {
        val levels = EqPresets.levels(EqPreset.BASS, bandCount = 5, maxMillibel = 1500)
        assertTrue("low band boosted", levels.first() > 0)
        assertEquals("high band flat", 0, levels.last())
    }

    @Test
    fun `treble boosts the high bands and leaves the low bands flat`() {
        val levels = EqPresets.levels(EqPreset.TREBLE, bandCount = 5, maxMillibel = 1500)
        assertEquals("low band flat", 0, levels.first())
        assertTrue("high band boosted", levels.last() > 0)
    }

    @Test
    fun `no level exceeds the max`() {
        EqPreset.entries.forEach { preset ->
            assertTrue(EqPresets.levels(preset, 10, 1500).all { it in 0..1500 })
        }
    }

    @Test
    fun `zero bands yields empty`() {
        assertTrue(EqPresets.levels(EqPreset.BASS, bandCount = 0, maxMillibel = 1500).isEmpty())
    }
}
