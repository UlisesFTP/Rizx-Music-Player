package fm.rizx.player.widget

import fm.rizx.player.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPaletteTest {

    @Test
    fun `the app's own choice wins, SYSTEM follows the device`() {
        assertEquals(WidgetPalette.Dark, WidgetPalette.resolve(ThemeMode.DARK, systemDark = false))
        assertEquals(WidgetPalette.Light, WidgetPalette.resolve(ThemeMode.LIGHT, systemDark = true))
        assertEquals(WidgetPalette.Dark, WidgetPalette.resolve(ThemeMode.SYSTEM, systemDark = true))
        assertEquals(WidgetPalette.Light, WidgetPalette.resolve(ThemeMode.SYSTEM, systemDark = false))
    }

    @Test
    fun `the two directions are the app's Ivory and Paper reds, not one shade`() {
        assertTrue(WidgetPalette.Dark.isDark)
        assertEquals(0xFFFF3B2F.toInt(), WidgetPalette.Dark.red)
        assertEquals(0xFFDE2A1E.toInt(), WidgetPalette.Light.red)
        assertNotEquals(WidgetPalette.Dark.cardRes, WidgetPalette.Light.cardRes)
        assertNotEquals("the play block is cream on ink and ink on paper", WidgetPalette.Dark.fill, WidgetPalette.Light.fill)
    }
}
