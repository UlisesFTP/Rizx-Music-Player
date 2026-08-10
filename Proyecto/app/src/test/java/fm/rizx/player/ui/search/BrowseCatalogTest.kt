package fm.rizx.player.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browse wall is a hand-maintained table, and every field of it is load-bearing: a duplicated id
 * means two tiles open the same screen, and a non-numeric one silently opens an empty genre (the
 * provider refuses it before the network, by design).
 */
class BrowseCatalogTest {

    @Test
    fun `every tile addresses a distinct numeric genre`() {
        val ids = BROWSE_CATEGORIES.map { it.genreId }

        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.isNotEmpty() && it.all(Char::isDigit) })
    }

    @Test
    fun `labels and HUD codes are unique so no two tiles read alike`() {
        assertEquals(BROWSE_CATEGORIES.size, BROWSE_CATEGORIES.map { it.labelRes }.toSet().size)
        assertEquals(BROWSE_CATEGORIES.size, BROWSE_CATEGORIES.map { it.code }.toSet().size)
    }

    /** The all-genres chart leads, and is the one tile Deezer publishes no artwork for. */
    @Test
    fun `the charts tile comes first and is the only one without artwork`() {
        val first = BROWSE_CATEGORIES.first()

        assertEquals("0", first.genreId)
        assertNull(first.image)
        assertTrue(BROWSE_CATEGORIES.drop(1).all { it.image != null })
    }

    @Test
    fun `the wall is materially wider than the sixteen genres it replaces`() {
        assertTrue("expected a broader catalogue", BROWSE_CATEGORIES.size >= 26)
    }

    @Test
    fun `a genre screen can find its tile's artwork by id, and shrugs off an unknown one`() {
        assertNotNull(browseCategoryFor("464"))
        assertEquals("464", browseCategoryFor("464")?.genreId)
        assertNull(browseCategoryFor("this-is-not-a-genre"))
    }
}
