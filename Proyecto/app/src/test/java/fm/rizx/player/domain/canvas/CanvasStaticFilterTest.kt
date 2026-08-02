package fm.rizx.player.domain.canvas

import fm.rizx.player.domain.model.CanvasAspect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether a YouTube upload will actually *move*.
 *
 * This is the fix for the complaint that mattered most: the canvas resolved a video, played it, and
 * showed a motionless square. Most of YouTube Music's catalogue is auto-generated `- Topic` uploads whose
 * whole "video" is the cover art, and in regional Mexican — most of this app's catalogue — the dominant
 * upload styles are `(Audio Oficial)` and `(Video Letra)`, neither of which the English-only wordlists in
 * `RecordingIdentity` would ever notice.
 */
class CanvasStaticFilterTest {

    private fun rate(title: String, uploader: String? = null, artist: String? = null) =
        CanvasStaticFilter.rate(title, uploader, artist)

    // ---- rejected: it will not move ----

    @Test
    fun `an auto-generated Topic channel is rejected outright`() {
        // Measured on device at 360x360 and perfectly still.
        assertEquals(CanvasSuitability.REJECTED, rate("GIRLS", uploader = "Luci - Topic"))
    }

    @Test
    fun `Official Audio is rejected`() {
        assertEquals(CanvasSuitability.REJECTED, rate("GIRLS (Official Audio)", uploader = "LuciVEVO"))
    }

    @Test
    fun `Audio Oficial is rejected too, which the English-only list never caught`() {
        assertEquals(CanvasSuitability.REJECTED, rate("TU NAME (Audio Oficial)", uploader = "Fuerza Regida"))
    }

    @Test
    fun `a bare Audio qualifier is the same upload with less ceremony`() {
        assertEquals(CanvasSuitability.REJECTED, rate("La Diabla (Audio)", uploader = "Xavi"))
    }

    @Test
    fun `a song actually called Audio is not rejected for its own name`() {
        // The qualifier rule reads bracketed positions only, so the title itself survives.
        assertEquals(CanvasSuitability.ACCEPTED, rate("Audio", uploader = "LSD"))
    }

    @Test
    fun `full albums and reactions are not this song's video`() {
        assertEquals(CanvasSuitability.REJECTED, rate("Fuerza Regida - Full Album 2024", uploader = "Mix"))
        assertEquals(CanvasSuitability.REJECTED, rate("GIRLS reaction", uploader = "Some Guy"))
        assertEquals(CanvasSuitability.REJECTED, rate("GIRLS reacción", uploader = "Otro Canal"))
    }

    @Test
    fun `Hot Topic is a song, not a channel`() {
        // "topic" is matched on the *uploader*, never the title — Le Tigre would otherwise never
        // get a canvas.
        assertEquals(CanvasSuitability.ACCEPTED, rate("Hot Topic", uploader = "Le Tigre"))
    }

    // ---- last resort: it moves, but it is not the film ----

    @Test
    fun `a lyric video is kept as a last resort rather than rejected`() {
        assertEquals(CanvasSuitability.LAST_RESORT, rate("GIRLS (Lyric Video)", uploader = "LuciVEVO"))
        assertEquals(CanvasSuitability.LAST_RESORT, rate("GIRLS (Lyrics)", uploader = "LuciVEVO"))
    }

    @Test
    fun `so is a Spanish video letra, which is most of this catalogue`() {
        assertEquals(CanvasSuitability.LAST_RESORT, rate("TU NAME (Video Letra)", uploader = "Fuerza Regida"))
        assertEquals(CanvasSuitability.LAST_RESORT, rate("La Diabla (Letra)", uploader = "Xavi"))
    }

    @Test
    fun `a visualizer moves but is not the song's film`() {
        assertEquals(CanvasSuitability.LAST_RESORT, rate("GIRLS (Visualizer)", uploader = "LuciVEVO"))
    }

    @Test
    fun `an Official Lyric Video is a lyric video first`() {
        // Both phrases are present; the weaker verdict has to win or the card outranks the film.
        assertEquals(CanvasSuitability.LAST_RESORT, rate("GIRLS (Official Lyric Video)", uploader = "LuciVEVO"))
    }

    // ---- preferred: the artist's own film ----

    @Test
    fun `Official Video is preferred, and is not confused with Official Audio`() {
        assertEquals(CanvasSuitability.PREFERRED, rate("GIRLS (Official Video)", uploader = "Some Channel"))
    }

    @Test
    fun `Video Oficial is preferred as well`() {
        assertEquals(CanvasSuitability.PREFERRED, rate("La Diabla (Video Oficial)", uploader = "Otro"))
    }

    @Test
    fun `a VEVO channel is the artist by another name`() {
        assertEquals(CanvasSuitability.PREFERRED, rate("Levitating", uploader = "DuaLipaVEVO"))
    }

    @Test
    fun `so is a channel that reads back as the artist`() {
        assertEquals(CanvasSuitability.PREFERRED, rate("La Diabla", uploader = "Xavi Oficial", artist = "Xavi"))
    }

    @Test
    fun `a stranger's upload of the right song is merely accepted`() {
        assertEquals(CanvasSuitability.ACCEPTED, rate("La Diabla", uploader = "Random Uploads", artist = "Xavi"))
    }

    // ---- the post-extraction veto ----

    @Test
    fun `a square frame from YouTube is cover art on a timeline`() {
        assertTrue(CanvasStaticFilter.isStillFrame(CanvasAspect.SQUARE))
    }

    @Test
    fun `a 16-9 frame is a real video`() {
        assertFalse(CanvasStaticFilter.isStillFrame(CanvasAspect.LANDSCAPE))
        assertFalse(CanvasStaticFilter.isStillFrame(CanvasAspect.PORTRAIT))
    }

    // ---- the word-boundary rule ----

    @Test
    fun `matching is by whole phrase, so a substring cannot masquerade as a tag`() {
        // The mistake this codebase already made once, in the Wikipedia bio gates: a bare `contains`
        // reads "abandoned" as a band and "adjacent" as a DJ.
        assertEquals(CanvasSuitability.ACCEPTED, rate("Letralandia", uploader = "Someone"))
        assertEquals(CanvasSuitability.ACCEPTED, rate("Audiophile", uploader = "Someone"))
    }

    @Test
    fun `accents fold, so Reacción is read the same as reaccion`() {
        assertEquals(CanvasSuitability.REJECTED, rate("GIRLS — REACCIÓN", uploader = "Canal"))
    }
}
