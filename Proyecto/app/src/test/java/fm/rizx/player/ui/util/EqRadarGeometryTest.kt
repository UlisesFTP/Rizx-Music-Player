package fm.rizx.player.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class EqRadarGeometryTest {

    @Test
    fun `the first vertex sits straight above the centre and the rest go clockwise`() {
        val (x0, y0) = EqRadarGeometry.polar(0, 4, 100f)
        assertEquals(180f, x0, 0.01f)
        assertEquals(62f, y0, 0.01f)
        val (x1, y1) = EqRadarGeometry.polar(1, 4, 100f)
        assertEquals(280f, x1, 0.01f)
        assertEquals(162f, y1, 0.01f)
        val (x2, y2) = EqRadarGeometry.polar(2, 4, 100f)
        assertEquals(180f, x2, 0.01f)
        assertEquals(262f, y2, 0.01f)
    }

    @Test
    fun `a band spans the inner ring to the outer edge of the shape`() {
        assertEquals(38f, EqRadarGeometry.shapeRadius(-15f, -15f, 15f), 0.001f)
        assertEquals(82f, EqRadarGeometry.shapeRadius(0f, -15f, 15f), 0.001f)
        assertEquals(126f, EqRadarGeometry.shapeRadius(15f, -15f, 15f), 0.001f)
        // Out-of-range values clamp instead of drawing outside the grid.
        assertEquals(126f, EqRadarGeometry.shapeRadius(40f, -15f, 15f), 0.001f)
        // A degenerate range still draws something sensible.
        assertEquals(82f, EqRadarGeometry.shapeRadius(3f, 0f, 0f), 0.001f)
    }

    @Test
    fun `decibels read like the design`() {
        assertEquals("0 dB", EqRadarGeometry.formatDecibels(0))
        assertEquals("0 dB", EqRadarGeometry.formatDecibels(4))
        assertEquals("+3 dB", EqRadarGeometry.formatDecibels(300))
        assertEquals("+2.4 dB", EqRadarGeometry.formatDecibels(240))
        assertEquals("−1.5 dB", EqRadarGeometry.formatDecibels(-150))
        assertEquals("−15 dB", EqRadarGeometry.formatDecibels(-1500))
    }

    @Test
    fun `frequencies switch to kilohertz at a thousand`() {
        assertEquals("31 Hz", EqRadarGeometry.frequencyLabel(31))
        assertEquals("250 Hz", EqRadarGeometry.frequencyLabel(250))
        assertEquals("1 kHz", EqRadarGeometry.frequencyLabel(1_000))
        assertEquals("16 kHz", EqRadarGeometry.frequencyLabel(16_000))
        assertEquals("3.6 kHz", EqRadarGeometry.frequencyLabel(3_600))
    }
}
