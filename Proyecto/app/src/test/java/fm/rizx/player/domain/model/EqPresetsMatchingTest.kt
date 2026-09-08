package fm.rizx.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EqPresetsMatchingTest {

    private val bands = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
        .mapIndexed { i, hz -> EqBandRange(i, hz, (hz * 0.7f).toInt(), (hz * 1.4f).toInt()) }

    @Test
    fun `the curve a preset wrote is recognised as that preset`() {
        for (preset in EqPreset.entries) {
            val levels = EqPresets.levels(preset, bands, -1500, 1500)
            val matched = EqPresets.matching(levels, bands, -1500, 1500)
            // Two presets can share a curve only if they are identical, in which case the first wins;
            // every one defined today produces its own curve.
            assertEquals(preset, matched)
        }
    }

    @Test
    fun `one nudged band turns a preset into a hand-made curve`() {
        val levels = EqPresets.levels(EqPreset.BASS, bands, -1500, 1500).toMutableList()
        levels[3] = levels[3] + 100
        assertNull(EqPresets.matching(levels, bands, -1500, 1500))
    }

    @Test
    fun `an all-zero curve is flat and nothing else matches nothing`() {
        assertEquals(EqPreset.FLAT, EqPresets.matching(List(10) { 0 }, bands, -1500, 1500))
        assertNull(EqPresets.matching(emptyList(), bands, -1500, 1500))
        assertNull(EqPresets.matching(List(5) { 0 }, bands, -1500, 1500))
    }
}
