package fm.rizx.player.ui.util

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListFilterTest {

    private fun track(title: String, artist: String = "", album: String? = null) = Track(
        title = title,
        artists = if (artist.isEmpty()) emptyList() else listOf(ArtistCredit(artist)),
        album = album?.let { AlbumRef(title = it, source = ProviderRef("deezer", "al")) },
        source = ProviderRef("deezer", title),
    )

    @Test
    fun `a blank query keeps everything`() {
        assertTrue(ListFilter.matches("", "Bow Down"))
        assertTrue(ListFilter.matches("   ", "Bow Down"))
        // Punctuation alone folds to nothing, which is still "nothing typed" rather than "match nothing".
        assertTrue(ListFilter.matches("¿?", "Bow Down"))
    }

    @Test
    fun `accents fold in both directions`() {
        assertTrue(ListFilter.matches("rosalia", "ROSALÍA"))
        assertTrue(ListFilter.matches("ROSALÍA", "rosalia"))
        assertTrue(ListFilter.matches("cancion", "Canción bonita"))
    }

    @Test
    fun `case is ignored`() {
        assertTrue(ListFilter.matches("FUERZA", "Fuerza Regida"))
        assertTrue(ListFilter.matches("fuerza regida", "FUERZA REGIDA"))
    }

    @Test
    fun `every word must appear, and any field may carry it`() {
        val killaz = track("Natural Born Killaz", artist = "Ice Cube")

        // One word from the title, one from the credit — the case a plain substring test cannot serve.
        assertTrue(ListFilter.matchesTrack("ice killaz", killaz))
        assertTrue(ListFilter.matchesTrack("cube natural", killaz))
        assertFalse("a word that appears nowhere rejects the row", ListFilter.matchesTrack("ice yellow", killaz))
    }

    @Test
    fun `a song is searched by title, credits and album`() {
        val song = track("Brillarosa", artist = "Fuerza Regida", album = "Pero No Te Enamores")

        assertTrue(ListFilter.matchesTrack("brilla", song))
        assertTrue(ListFilter.matchesTrack("regida", song))
        assertTrue(ListFilter.matchesTrack("enamores", song))
        assertFalse(ListFilter.matchesTrack("deftones", song))
    }

    @Test
    fun `a track with no artist or album is still matched on what it has`() {
        // Exactly the on-device scan in the screenshots: file names, "Unknown artist", no album.
        val scanned = track("AUD-20260222-WA0007")

        assertTrue(ListFilter.matchesTrack("wa0007", scanned))
        assertTrue(ListFilter.matchesTrack("aud 2026", scanned))
        assertFalse(ListFilter.matchesTrack("deftones", scanned))
    }

    @Test
    fun `punctuation is dropped, so a hyphen or apostrophe cannot hide a row`() {
        assertTrue(ListFilter.matches("dont stop", "Don't Stop Me Now"))
        assertTrue(ListFilter.matches("hiphop", "Hip-Hop"))
        assertTrue(ListFilter.matches("hip hop", "Hip-Hop"))
        // Dropping the ampersand glues the two letters, which is why "rb" reaches "R&B" — "rnb" is a
        // different spelling of the *sound*, not of the string, and is deliberately not matched.
        assertTrue(ListFilter.matches("rb", "R&B"))
    }

    @Test
    fun `separated words do not glue together`() {
        // "Ice Cube, Dr. Dre" must not become "ice cubedr dre" and cost "cube" its match.
        assertTrue(ListFilter.matches("cube", "Ice Cube, Dr. Dre"))
        assertTrue(ListFilter.matches("dre", "Ice Cube, Dr. Dre"))
    }

    @Test
    fun `null fields are skipped rather than matched`() {
        assertTrue(ListFilter.matches("bow", "Bow Down", null))
        assertFalse(ListFilter.matches("westside", "Bow Down", null))
    }

    @Test
    fun `non-latin titles survive folding`() {
        // `\p{L}` rather than `a-z`: stripping these would leave an empty haystack and an unsearchable row.
        assertTrue(ListFilter.matches("東京", "東京事変"))
        assertTrue(ListFilter.matches("Кино", "кино"))
    }

    @Test
    fun `folding is idempotent and collapses whitespace`() {
        assertEquals("ice cube", ListFilter.fold("  Ice   Cube  "))
        assertEquals(ListFilter.fold("Rosalía"), ListFilter.fold(ListFilter.fold("Rosalía")))
    }
}
