package fm.rizx.player.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings search. What matters is not the matcher — `ListFilter` has its own tests — but that a
 * row is reachable by **any** of the three things it shows, and that a group whose rows all filtered
 * out leaves no orphaned heading behind.
 */
class SettingsFilterTest {

    /** Three real rows, keyed the way the screen keys them: title, then value, then caption. */
    private val sound = listOf(
        entry("Equalizer", "Presets · bands") {},
        entry("Automatic equalizer", "A curve per song, from its genre and the recording itself.") {},
        entry("Audio quality", "Standard", "48 kHz output · EQ and normalization apply after the player") {},
    )

    @Test
    fun `a blank query leaves every row standing`() {
        assertEquals(sound, visibleEntries("", sound))
    }

    @Test
    fun `a row is findable by its title`() {
        assertEquals(listOf("Audio quality"), visibleEntries("quality", sound).map { it.key })
    }

    @Test
    fun `a row is findable by its value, not only its title`() {
        // Nobody looks for "Equalizer" when what they remember is the word "presets".
        assertEquals(listOf("Equalizer"), visibleEntries("presets", sound).map { it.key })
    }

    @Test
    fun `a row is findable by its caption`() {
        // "48 kHz" appears nowhere in the title or the value — only in the explanation.
        assertEquals(listOf("Audio quality"), visibleEntries("48 kHz", sound).map { it.key })
    }

    @Test
    fun `every word of the query has to appear somewhere in the row`() {
        assertTrue(visibleEntries("automatic equalizer", sound).map { it.key } == listOf("Automatic equalizer"))
        assertTrue(visibleEntries("automatic trombone", sound).isEmpty())
    }

    @Test
    fun `a group whose rows all filtered out is empty, so its heading is never drawn alone`() {
        assertTrue(visibleEntries("bluetooth", sound).isEmpty())
    }

    @Test
    fun `the key is the first thing the row says, so a row keeps its identity while filtering`() {
        // Rows are re-keyed as neighbours disappear; an unstable key would let Compose carry one row's
        // state onto another.
        assertEquals("Equalizer", sound.first().key)
        assertEquals(sound.map { it.key }, visibleEntries("", sound).map { it.key })
    }
}
