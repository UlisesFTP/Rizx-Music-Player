package fm.rizx.player.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class DotBarTest {

    @Test
    fun `only whole columns fit, and the bar is exactly as wide as they are`() {
        // 100px, 6px cells, 2px gaps: 12 columns need 12*6 + 11*2 = 94px; a 13th would need 102.
        val geometry = DotBar.geometry(widthPx = 100, cellPx = 6, gapPx = 2, rows = 3)

        assertEquals(12, geometry.columns)
        assertEquals(94, geometry.widthPx)
        assertEquals(3 * 6 + 2 * 2, geometry.heightPx)
    }

    @Test
    fun `no room means no columns and a zero-width bar`() {
        assertEquals(0, DotBar.geometry(0, 6, 2, 3).columns)
        assertEquals(0, DotBar.geometry(5, 6, 2, 3).columns)
        assertEquals(0, DotBar.geometry(0, 6, 2, 3).widthPx)
        assertEquals(1, DotBar.geometry(6, 6, 2, 3).columns)
    }

    @Test
    fun `a tap zone stands for the middle of its slice of the song`() {
        assertEquals(0.5f / 24f, DotBar.tapFraction(0, 24), 1e-6f)
        assertEquals(12.5f / 24f, DotBar.tapFraction(12, 24), 1e-6f)
        assertEquals(23.5f / 24f, DotBar.tapFraction(23, 24), 1e-6f)
        assertEquals("clamped to the last zone", 23.5f / 24f, DotBar.tapFraction(99, 24), 1e-6f)
        assertEquals("clamped to the first zone", 0.5f / 24f, DotBar.tapFraction(-3, 24), 1e-6f)
    }

    @Test
    fun `filled columns round, so the last square lights only at the very end`() {
        assertEquals(0, DotBar.filledColumns(40, 0f))
        assertEquals(0, DotBar.filledColumns(40, 0.01f))
        assertEquals(20, DotBar.filledColumns(40, 0.5f))
        assertEquals(39, DotBar.filledColumns(40, 0.98f))
        assertEquals(40, DotBar.filledColumns(40, 1f))
        assertEquals("clamped", 40, DotBar.filledColumns(40, 7f))
        assertEquals("clamped", 0, DotBar.filledColumns(40, -1f))
    }
}
