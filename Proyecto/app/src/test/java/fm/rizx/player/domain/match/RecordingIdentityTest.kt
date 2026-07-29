package fm.rizx.player.domain.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the line between "same song" and "same recording".
 *
 * Every rejection case here is a cover the app was actually showing wrongly: the enricher took a
 * catalogue's first search hit without checking it, so a remix's or a stranger's artwork ended up on
 * the track — and then got written to disk.
 */
class RecordingIdentityTest {

    // ---- Rejections: a different recording legitimately has different artwork ----

    @Test
    fun `a remix is not the original — the real bug, reproduced`() {
        // Verified live against Deezer: searching "Surf7 GOOSE COAT" returns the Meek Mill remix,
        // whose cover is a different image entirely.
        assertFalse(RecordingIdentity.sameTitle("GOOSE COAT", "GOOSE COAT (Remix) [feat. Meek Mill]"))
    }

    @Test
    fun `a live take is not the studio take, in either language`() {
        assertFalse(RecordingIdentity.sameTitle("El Hijo Mayor", "El Hijo Mayor [En Vivo]"))
        assertFalse(RecordingIdentity.sameTitle("Lithium", "Lithium (Live)"))
    }

    @Test
    fun `acoustic, karaoke, instrumental and sped-up are all their own recordings`() {
        val original = "Save Your Tears"
        for (other in listOf(
            "Save Your Tears (Acoustic)",
            "Save Your Tears - Karaoke Version",
            "Save Your Tears (Instrumental)",
            "Save Your Tears (Sped Up)",
        )) {
            assertFalse(other, RecordingIdentity.sameTitle(original, other))
        }
    }

    @Test
    fun `a remaster is a distinct recording`() {
        assertFalse(RecordingIdentity.sameTitle("Lithium", "Lithium (Remastered)"))
    }

    // ---- sharesArtwork: looser on purpose, because it asks a different question ----

    @Test
    fun `a remaster and a radio edit may donate their cover — they ship under the original art`() {
        // Catalogue rows carry "(Remastered 2011)" and "(Radio Edit)" constantly. Rejecting them
        // would trade the wrong-cover bug for a missing-cover bug on very common rows.
        assertTrue(RecordingIdentity.sharesArtwork("Bohemian Rhapsody", "Bohemian Rhapsody (Remastered 2011)"))
        assertTrue(RecordingIdentity.sharesArtwork("Blinding Lights", "Blinding Lights - Radio Edit"))
        assertFalse(RecordingIdentity.sameTitle("Bohemian Rhapsody", "Bohemian Rhapsody (Remastered 2011)"))
    }

    @Test
    fun `sharesArtwork still refuses a remix, a live take and a karaoke version`() {
        assertFalse(RecordingIdentity.sharesArtwork("GOOSE COAT", "GOOSE COAT (Remix) [feat. Meek Mill]"))
        assertFalse(RecordingIdentity.sharesArtwork("El Hijo Mayor", "El Hijo Mayor [En Vivo]"))
        assertFalse(RecordingIdentity.sharesArtwork("Save Your Tears", "Save Your Tears (Karaoke Version)"))
    }

    // ---- Qualifier-scoped scanning: a version word only counts where it means one ----

    @Test
    fun `a version word inside the actual title is not a version tag`() {
        // A whole-title scan calls these a live take and a radio edit; they are neither.
        assertTrue(RecordingIdentity.versionTags("Live and Let Die").isEmpty())
        assertTrue(RecordingIdentity.versionTags("Radio Ga Ga").isEmpty())
        assertTrue(RecordingIdentity.sameTitle("Live and Let Die", "Live and Let Die"))
    }

    @Test
    fun `a glued version word is read like a spaced one`() {
        assertEquals(RecordingIdentity.versionTags("Song (Sped Up)"), RecordingIdentity.versionTags("Song (SpedUp)"))
        assertTrue(RecordingIdentity.versionTags("Song (SpedUp)").isNotEmpty())
    }

    @Test
    fun `two different songs never match`() {
        assertFalse(RecordingIdentity.sameTitle("Blinding Lights", "Save Your Tears"))
    }

    @Test
    fun `a short title does not match by containment`() {
        // The title-only cache-key bug gave every song called "Intro" one stranger's cover.
        assertFalse(RecordingIdentity.sameTitle("Intro", "Introduction To Everything"))
        assertFalse(RecordingIdentity.sameTitle("Intro", "Intro (Skit)"))
    }

    // ---- Acceptances: platform decoration says nothing about the recording ----

    @Test
    fun `a YouTube row matches the catalogue title it decorates`() {
        // These are the rows that most need a borrowed cover, so rejecting them would be its own bug.
        assertTrue(RecordingIdentity.sameTitle("Xavi - La Diabla (Official Video)", "La Diabla"))
        assertTrue(RecordingIdentity.sameTitle("Evanescence - Bring Me To Life (Official HD Music Video)", "Bring Me To Life"))
    }

    @Test
    fun `a SoundCloud free-download tag is decoration, not a version`() {
        assertTrue(RecordingIdentity.sameTitle("Know Me Better [FREE DL]", "Know Me Better"))
    }

    @Test
    fun `feat credits, punctuation and accents fold away`() {
        assertTrue(RecordingIdentity.sameTitle("Get Lucky (feat. Pharrell Williams)", "Get Lucky"))
        assertTrue(RecordingIdentity.sameTitle("Harder, Better, Faster, Stronger", "Harder Better Faster Stronger"))
        assertTrue(RecordingIdentity.sameTitle("Corazón Partío", "Corazon Partio"))
    }

    @Test
    fun `the same version on both sides still matches`() {
        assertTrue(RecordingIdentity.sameTitle("Lithium (Live)", "Lithium - Live"))
        assertTrue(RecordingIdentity.sameTitle("Song (Acoustic) [Official Video]", "Song (Acoustic)"))
    }

    // ---- The pieces, directly ----

    @Test
    fun `versionTags is order-independent and duplicate-free`() {
        assertEquals(
            RecordingIdentity.versionTags("Song (Live) [Acoustic]"),
            RecordingIdentity.versionTags("Song (Acoustic) [Live]"),
        )
        assertEquals(setOf("live"), RecordingIdentity.versionTags("Song (Live) [Live]"))
        assertTrue(RecordingIdentity.versionTags("Plain Song").isEmpty())
    }

    @Test
    fun `titleStem drops decoration whole, leaving no stray words behind`() {
        // "(Official Music Video)" must not leave a "music" in the stem.
        assertEquals("la diabla", RecordingIdentity.titleStem("La Diabla (Official Music Video)"))
        assertEquals("bring me to life", RecordingIdentity.titleStem("Bring Me To Life (Official HD Music Video)"))
    }

    @Test
    fun `a title that is only decoration yields an empty stem, which never matches`() {
        assertEquals("", RecordingIdentity.titleStem("(Official Video)"))
        assertFalse(RecordingIdentity.sameTitle("(Official Video)", "Anything"))
    }
}
