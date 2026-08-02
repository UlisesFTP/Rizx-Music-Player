package fm.rizx.player.data.lossless

import fm.rizx.player.domain.lossless.LosslessIndexItem
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule these all enforce: **a correct ordinary stream beats a wrong FLAC**.
 *
 * The reference implementation this replaces matched with `contains`, which is how a player ends up
 * confidently playing somebody else's song of the same name at higher fidelity. Every case below is a
 * way that can happen, and every one of them has to end in a rejection rather than a lower score.
 */
class DefaultLosslessMatcherTest {

    private val matcher = DefaultLosslessMatcher()

    // ---- stage one: title ----

    @Test
    fun `accepts an exact title and artist`() {
        val hits = matcher.candidates(track("Blinding Lights", "The Weeknd"), listOf(row("Blinding Lights", "The Weeknd")))

        assertEquals(1, hits.size)
        assertTrue(hits[0].evidence.titleMatched && hits[0].evidence.artistMatched)
    }

    @Test
    fun `ignores platform decoration in the index title`() {
        // "(Official Video)" says where the upload came from, not which recording it is.
        val hits = matcher.candidates(
            track("Blinding Lights", "The Weeknd"),
            listOf(row("Blinding Lights (Official Video)", "The Weeknd")),
        )

        assertEquals(1, hits.size)
    }

    @Test
    fun `rejects every version tag that means a different recording`() {
        val original = track("Blinding Lights", "The Weeknd")
        val decorated = listOf(
            "Blinding Lights (Remix)",
            "Blinding Lights (Live)",
            "Blinding Lights (Acoustic)",
            "Blinding Lights (Sped Up)",
            "Blinding Lights (Slowed + Reverb)",
            "Blinding Lights (Karaoke)",
        )

        decorated.forEach { title ->
            assertTrue("$title should not match the album version", matcher.candidates(original, listOf(row(title, "The Weeknd"))).isEmpty())
        }
    }

    @Test
    fun `rejects the original when the track itself is the remix`() {
        // Symmetric on purpose: playing the album cut behind a remix is the same error backwards.
        val remix = track("Blinding Lights (Remix)", "The Weeknd")

        assertTrue(matcher.candidates(remix, listOf(row("Blinding Lights", "The Weeknd"))).isEmpty())
    }

    // ---- stage one: artist ----

    @Test
    fun `rejects the same title by a different artist`() {
        assertTrue(
            matcher.candidates(track("Girls", "Rita Ora"), listOf(row("Girls", "The 1975"))).isEmpty(),
        )
    }

    @Test
    fun `normalises VEVO and Topic decorations on the artist`() {
        val t = track("Blinding Lights", "The Weeknd")

        assertEquals(1, matcher.candidates(t, listOf(row("Blinding Lights", "TheWeekndVEVO"))).size)
        assertEquals(1, matcher.candidates(t, listOf(row("Blinding Lights", "The Weeknd - Topic"))).size)
    }

    @Test
    fun `accepts a compatible collaboration and scores the secondary credit`() {
        val t = track("Pepas", "Farruko", "Sech")
        val hits = matcher.candidates(t, listOf(row("Pepas", "Farruko, Sech")))

        assertEquals(1, hits.size)
        assertTrue("the second credit lining up is real corroboration", hits[0].matchScore > 85)
    }

    @Test
    fun `refuses a track with no artist at all`() {
        // "Intro" would otherwise match every album ever made.
        val anonymous = Track(title = "Intro", artists = emptyList(), durationMs = 60_000, source = ref("deezer", "1"))

        assertTrue(matcher.candidates(anonymous, listOf(row("Intro", "Some Band"))).isEmpty())
    }

    // ---- stage one: album and ISRC (modelled, absent from the minimal index) ----

    @Test
    fun `an agreeing album adds confidence`() {
        val t = track("Save Your Tears", "The Weeknd", album = "After Hours")
        val plain = matcher.candidates(t, listOf(row("Save Your Tears", "The Weeknd")))
        val withAlbum = matcher.candidates(t, listOf(row("Save Your Tears", "The Weeknd", album = "After Hours")))

        assertTrue(withAlbum[0].matchScore > plain[0].matchScore)
        assertEquals(true, withAlbum[0].evidence.albumMatched)
    }

    @Test
    fun `a contradicting album is a rejection, not a deduction`() {
        val t = track("Save Your Tears", "The Weeknd", album = "After Hours")

        assertTrue(
            matcher.candidates(t, listOf(row("Save Your Tears", "The Weeknd", album = "Starboy"))).isEmpty(),
        )
    }

    @Test
    fun `a missing album on either side is silence, not disagreement`() {
        // The whole minimal index looks like this, so treating absence as a mismatch would reject
        // everything it publishes.
        val hits = matcher.candidates(track("Pepas", "Farruko", album = "La 167"), listOf(row("Pepas", "Farruko")))

        assertEquals(1, hits.size)
        assertNull(hits[0].evidence.albumMatched)
    }

    @Test
    fun `an agreeing ISRC dominates and a contradicting one rejects`() {
        val t = track("Pepas", "Farruko").copy(tags = listOf("isrc=USSD12100123"))

        val agree = matcher.candidates(t, listOf(row("Pepas", "Farruko", isrc = "ussd12100123")))
        assertEquals(true, agree[0].evidence.isrcMatched)
        assertTrue(agree[0].matchScore > 150)

        val disagree = matcher.candidates(t, listOf(row("Pepas", "Farruko", isrc = "GBAAA0000001")))
        assertTrue("a different recording is a different recording", disagree.isEmpty())
    }

    @Test
    fun `an index duration that is wildly off rejects before a byte is fetched`() {
        val t = track("Pepas", "Farruko", durationMs = 287_000)

        assertTrue(matcher.candidates(t, listOf(row("Pepas", "Farruko", durationMs = 120_000))).isEmpty())
        assertEquals(1, matcher.candidates(t, listOf(row("Pepas", "Farruko", durationMs = 288_000))).size)
    }

    // ---- stage two: the file's own duration ----

    @Test
    fun `a duration matching the file confirms a minimal-index candidate`() {
        val t = track("Pepas", "Farruko", durationMs = 287_000)
        val candidate = matcher.candidates(t, listOf(row("Pepas", "Farruko"))).single()

        val confirmed = matcher.confirmWithDuration(t, candidate, flacDurationMs = 288_000)

        assertNotNull(confirmed)
        assertEquals(true, confirmed!!.evidence.durationMatched)
    }

    @Test
    fun `a duration off by more than ten seconds is rejected outright`() {
        val t = track("Pepas", "Farruko", durationMs = 287_000)
        val candidate = matcher.candidates(t, listOf(row("Pepas", "Farruko"))).single()

        assertNull(matcher.confirmWithDuration(t, candidate, flacDurationMs = 210_000))
    }

    @Test
    fun `a six-second difference is not good enough on title and artist alone`() {
        // The band between "close" and "clearly wrong" — beyond the tolerance, inside the hard limit.
        // With nothing but a title and an artist agreeing, six seconds on a two-minute song is a radio
        // edit or a different take, so it falls below the final threshold.
        val t = track("Ella Baila Sola", "Eslabon Armado", durationMs = 120_000)
        val candidate = matcher.candidates(t, listOf(row("Ella Baila Sola", "Eslabon Armado"))).single()

        assertNull(matcher.confirmWithDuration(t, candidate, flacDurationMs = 126_000))
    }

    @Test
    fun `the same six seconds survives when an album also corroborated`() {
        val t = track("Ella Baila Sola", "Eslabon Armado", durationMs = 120_000, album = "Desvelado")
        val candidate = matcher
            .candidates(t, listOf(row("Ella Baila Sola", "Eslabon Armado", album = "Desvelado")))
            .single()

        assertNotNull(matcher.confirmWithDuration(t, candidate, flacDurationMs = 126_000))
    }

    @Test
    fun `the tolerance is three percent, so a long song forgives more than a short one`() {
        // 3% of 4:47 is 8.6s; the same 6s that fails on a two-minute song is normal encoder drift here.
        val t = track("Pepas", "Farruko", durationMs = 287_000)
        val candidate = matcher.candidates(t, listOf(row("Pepas", "Farruko"))).single()

        assertNotNull(matcher.confirmWithDuration(t, candidate, flacDurationMs = 293_000))
    }

    @Test
    fun `matching only a guest credit is not enough on its own`() {
        // The index credits a song "The 1975, Rita Ora" and our track is by Rita Ora. Her name really is
        // in there — which is exactly why the primary credit has to be worth more than a mention.
        val t = track("Girls", "Rita Ora")

        assertTrue(matcher.candidates(t, listOf(row("Girls", "The 1975, Rita Ora"))).isEmpty())
    }

    @Test
    fun `a track with no duration of its own cannot be confirmed at all`() {
        // Both witnesses silent is not agreement — it is exactly the case where an album version and a
        // single edit are indistinguishable.
        val t = track("Pepas", "Farruko", durationMs = null)
        val candidate = matcher.candidates(t, listOf(row("Pepas", "Farruko"))).single()

        assertNull(matcher.confirmWithDuration(t, candidate, flacDurationMs = 287_000))
    }

    @Test
    fun `the duration tolerance grows with the length of the song`() {
        // 3% of a fourteen-minute track is 25 seconds, which the flat 4s floor would have rejected.
        val long = track("Echoes", "Pink Floyd", durationMs = 1_400_000)
        val candidate = matcher.candidates(long, listOf(row("Echoes", "Pink Floyd"))).single()

        assertNotNull(matcher.confirmWithDuration(long, candidate, flacDurationMs = 1_415_000))
    }

    // ---- ambiguity ----

    @Test
    fun `two candidates within five points are too close to call`() {
        assertTrue(matcher.tooCloseToCall(90, 88))
        assertTrue(matcher.tooCloseToCall(90, 90))
        assertFalse(matcher.tooCloseToCall(90, 80))
    }

    @Test
    fun `candidates come back best first, so nothing has to take rank one on trust`() {
        val t = track("Pepas", "Farruko", "Sech", album = "La 167", durationMs = 287_000)
        val hits = matcher.candidates(
            t,
            listOf(
                row("Pepas", "Farruko"),
                row("Pepas", "Farruko, Sech", album = "La 167", durationMs = 287_000),
            ),
        )

        assertEquals(2, hits.size)
        assertTrue("the corroborated row must lead", hits[0].matchScore > hits[1].matchScore)
        assertEquals("La 167", hits[0].item.album)
    }

    // ---- fixtures ----

    private fun track(
        title: String,
        vararg artists: String,
        album: String? = null,
        durationMs: Long? = 287_000,
    ) = Track(
        title = title,
        artists = artists.map { ArtistCredit(name = it) },
        album = album?.let { AlbumRef(title = it, source = ref("deezer", "album:1")) },
        durationMs = durationMs,
        source = ref("deezer", "track:1"),
    )

    private fun row(
        song: String,
        artist: String,
        album: String? = null,
        durationMs: Long? = null,
        isrc: String? = null,
        url: String = "https://host.example/music/${song.hashCode()}.flac",
    ) = LosslessIndexItem(song = song, artist = artist, url = url, album = album, durationMs = durationMs, isrc = isrc)

    private fun ref(provider: String, id: String) = ProviderRef(provider, id)
}
