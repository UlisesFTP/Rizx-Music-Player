package fm.rizx.player.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistNameMatchingTest {

    @Test
    fun `a channel name is searched as the artist behind it`() {
        // Verified live: Deezer's artist search returns nothing for "ModjoOfficial" and finds the band
        // for "modjo" — so the query has to be de-channelized, not just the comparison.
        assertEquals("modjo", ArtistNameMatching.searchName("ModjoOfficial"))
        assertEquals("dualipa", ArtistNameMatching.searchName("DuaLipaVEVO"))
        assertEquals("radiohead", ArtistNameMatching.searchName("Radiohead - Topic"))
    }

    @Test
    fun `a channel word at the front is handled too`() {
        // Seen live: the channel is "Official Arctic Monkeys", and Deezer answers nothing for that.
        assertEquals("arcticmonkeys", ArtistNameMatching.searchName("Official Arctic Monkeys"))
        assertTrue(ArtistNameMatching.sameArtist("Official Arctic Monkeys", "Arctic Monkeys"))
    }

    @Test
    fun `a real name starting with a channel word is not mangled`() {
        // Only the unambiguous words are stripped from the front — "Band of Horses" is a band's name,
        // not a decorated one.
        assertEquals("Band of Horses", ArtistNameMatching.searchName("Band of Horses"))
        assertEquals("TV on the Radio", ArtistNameMatching.searchName("TV on the Radio"))
        assertEquals("Music Soulchild", ArtistNameMatching.searchName("Music Soulchild"))
    }

    @Test
    fun `an ordinary artist name is left exactly as it is`() {
        assertEquals("Bad Bunny", ArtistNameMatching.searchName("Bad Bunny"))
        assertEquals("Rosalía", ArtistNameMatching.searchName("Rosalía"))
        // Not a suffix — "Music" leads the name here.
        assertEquals("Music Soulchild", ArtistNameMatching.searchName("Music Soulchild"))
    }

    @Test
    fun `a short name keeps its trailing word rather than collapsing to a stem`() {
        // "The Band" → "the" would match every other "The …".
        assertEquals("The Band", ArtistNameMatching.searchName("The Band"))
        assertFalse(ArtistNameMatching.sameArtist("The Band", "The Music"))
    }

    @Test
    fun `the raw name is still tried after the stem`() {
        assertEquals(listOf("modjo", "ModjoOfficial"), ArtistNameMatching.queries("ModjoOfficial"))
        // Nothing to strip: one query, not two identical ones.
        assertEquals(listOf("Coldplay"), ArtistNameMatching.queries("Coldplay"))
    }

    @Test
    fun `the same artist is recognised across case, accents and channel decoration`() {
        assertTrue(ArtistNameMatching.sameArtist("ROSALIA", "Rosalía"))
        assertTrue(ArtistNameMatching.sameArtist("ModjoOfficial", "Modjo"))
        assertTrue(ArtistNameMatching.sameArtist("Dua Lipa", "DuaLipaVEVO"))
    }

    @Test
    fun `a different artist is not`() {
        assertFalse(ArtistNameMatching.sameArtist("Coldplay", "Coldplay Tribute Band"))
        assertFalse(ArtistNameMatching.sameArtist("Modjo", "Mojo"))
        assertFalse(ArtistNameMatching.sameArtist("", "Modjo"))
    }
}
