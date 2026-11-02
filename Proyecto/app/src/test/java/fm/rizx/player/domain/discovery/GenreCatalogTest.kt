package fm.rizx.player.domain.discovery

import fm.rizx.player.domain.model.SoundGenre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreCatalogTest {

    @Test
    fun `Deezer genre ids map to the family the shelf browses`() {
        assertEquals(SoundGenre.REGGAETON, GenreCatalog.fromDeezerGenre(122))
        assertEquals(SoundGenre.METAL, GenreCatalog.fromDeezerGenre(464))
        // Folded families: Deezer's Dance shelf is browsed as Electronic, Cumbia as Tropical.
        assertEquals(SoundGenre.ELECTRONIC, GenreCatalog.fromDeezerGenre(113))
        assertEquals(SoundGenre.LATIN_TROPICAL, GenreCatalog.fromDeezerGenre(71))
        assertEquals(SoundGenre.UNKNOWN, GenreCatalog.fromDeezerGenre(999_999))
        assertEquals(SoundGenre.UNKNOWN, GenreCatalog.fromDeezerGenre(null))
    }

    @Test
    fun `every Spotify id is an editorial playlist id`() {
        // Editorial ids start with 37i9dQZF1 and are 22 base-62 characters; anything else was a typo.
        val ids = SoundGenre.entries.flatMap { GenreCatalog.entry(it)?.spotifyPlaylistIds.orEmpty() }
        assertTrue(ids.isNotEmpty())
        ids.forEach { assertTrue(it, it.matches(Regex("37i9dQZF1[0-9A-Za-z]{13}"))) }
        assertEquals("no id twice", ids.size, ids.toSet().size)
    }

    @Test
    fun `genres with nothing to browse have no entry`() {
        assertNull(GenreCatalog.entry(SoundGenre.SPOKEN))
        assertNull(GenreCatalog.entry(SoundGenre.UNKNOWN))
        assertTrue(SoundGenre.POP in GenreCatalog.browsable)
    }

    @Test
    fun `Apple curator ids are numeric`() {
        SoundGenre.entries.mapNotNull { GenreCatalog.entry(it)?.appleCurator }.forEach {
            assertTrue(it.id, it.id.all(Char::isDigit))
        }
    }
}
