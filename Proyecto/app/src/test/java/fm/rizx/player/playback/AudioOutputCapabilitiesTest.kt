package fm.rizx.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-formatter tests for the Hi-Res output capability label (no Android framework involved). */
class AudioOutputCapabilitiesTest {

    @Test
    fun `full capability lists native, max and float`() {
        assertEquals(
            "48 kHz output · up to 192 kHz · 32-bit float",
            formatOutputLabel(nativeHz = 48_000, maxHz = 192_000, floatCapable = true),
        )
    }

    @Test
    fun `native only when max is unknown`() {
        assertEquals("48 kHz output", formatOutputLabel(48_000, maxHz = null, floatCapable = false))
    }

    @Test
    fun `max equal to native is not shown as 'up to'`() {
        assertEquals("48 kHz output", formatOutputLabel(48_000, maxHz = 48_000, floatCapable = false))
    }

    @Test
    fun `non-integer kHz rates render with one decimal`() {
        assertEquals("44.1 kHz output · up to 192 kHz", formatOutputLabel(44_100, 192_000, floatCapable = false))
        assertEquals("88.2 kHz output", formatOutputLabel(88_200, maxHz = null, floatCapable = false))
    }

    @Test
    fun `max without a native rate still shows 'up to'`() {
        assertEquals("up to 96 kHz · 32-bit float", formatOutputLabel(nativeHz = null, maxHz = 96_000, floatCapable = true))
    }

    @Test
    fun `nothing known reports unknown`() {
        assertEquals("Output capability unknown", formatOutputLabel(nativeHz = null, maxHz = null, floatCapable = false))
    }
}
