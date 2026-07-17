package fm.rizx.player.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `formats whole minutes and seconds`() {
        assertEquals("0:00", formatClock(0.0))
        assertEquals("0:05", formatClock(5.0))
        assertEquals("1:24", formatClock(84.0))
        assertEquals("3:24", formatClock(204.0))
    }

    @Test
    fun `floors fractional seconds`() {
        assertEquals("0:05", formatClock(5.9))
        assertEquals("1:00", formatClock(60.4))
    }

    @Test
    fun `clamps negatives to zero`() {
        assertEquals("0:00", formatClock(-12.0))
    }
}
