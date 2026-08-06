package fm.rizx.player.data.recognition

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.recognition.RecognitionMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the catalogue is allowed to answer with.
 *
 * The failure mode this guards against is specific: a search for a recognised song returns the studio
 * version, a live take, a karaoke backing track and someone's cover, all with the same title, and
 * picking the wrong one plays confidently wrong audio. Every case here is a version of that.
 */
class RecognitionMatcherTest {

    private val matcher = RecognitionMatcher()

    private val heard = RecognitionMatch(
        provider = "shazam",
        providerTrackId = "105842472",
        title = "Get Lucky",
        artist = "Daft Punk, Pharrell Williams & Nile Rodgers",
        album = "Random Access Memories",
        isrc = "USQX91300108",
    )

    private fun track(
        title: String,
        artists: List<String> = listOf("Daft Punk"),
        album: String? = "Random Access Memories",
        isrc: String? = null,
        id: String = "1",
    ) = Track(
        title = title,
        artists = artists.map { ArtistCredit(name = it) },
        album = album?.let { AlbumRef(title = it, source = ProviderRef("deezer", "album:1")) },
        tags = listOfNotNull(isrc?.let { "isrc=$it" }),
        source = ProviderRef("deezer", id),
    )

    @Test
    fun `the studio version is chosen over a live take and a karaoke backing track`() {
        val chosen = matcher.best(
            heard,
            listOf(
                track("Get Lucky (Live)", id = "live"),
                track("Get Lucky (Karaoke Version)", id = "karaoke"),
                track("Get Lucky", id = "studio"),
            ),
        )
        assertEquals("studio", chosen?.source?.id)
    }

    @Test
    fun `a cover by another artist never wins`() {
        assertNull(matcher.best(heard, listOf(track("Get Lucky", artists = listOf("Some Tribute Band")))))
    }

    @Test
    fun `nothing convincing means nothing is played`() {
        val chosen = matcher.best(
            heard,
            listOf(
                track("Get Lucky (Remix)"),
                track("Lucky", artists = listOf("Britney Spears"), album = "Oops!... I Did It Again"),
                track("Get Lucky", artists = listOf("Daughtry"), album = "Baptized"),
            ),
        )
        assertNull(chosen)
    }

    @Test
    fun `a collaboration billed as one line still matches a catalogue that splits it`() {
        // The service says "Daft Punk, Pharrell Williams & Nile Rodgers"; the catalogue credits one act.
        assertTrue(matcher.accepts(heard, track("Get Lucky", artists = listOf("Daft Punk"))))
    }

    @Test
    fun `an artist buried in the billing is not enough on its own`() {
        val guest = heard.copy(album = null)
        assertFalse(matcher.accepts(guest, track("Get Lucky", artists = listOf("Nile Rodgers"), album = null)))
    }

    @Test
    fun `a guest credit is enough once the album agrees`() {
        assertTrue(matcher.accepts(heard, track("Get Lucky", artists = listOf("Nile Rodgers"))))
    }

    @Test
    fun `an agreeing album breaks the tie between two otherwise equal rows`() {
        val chosen = matcher.best(
            heard,
            listOf(
                track("Get Lucky", album = "Now That's What I Call Music! 85", id = "compilation"),
                track("Get Lucky", album = "Random Access Memories", id = "original"),
            ),
        )
        assertEquals("original", chosen?.source?.id)
    }

    @Test
    fun `a differing album never vetoes an otherwise perfect match`() {
        // The same recording lives on compilations, reissues and regional editions. Rejecting those
        // would mean finding nothing for a large part of the catalogue.
        assertTrue(matcher.accepts(heard, track("Get Lucky", album = "Greatest Hits")))
    }

    @Test
    fun `a contradicting isrc is fatal however well everything else reads`() {
        assertFalse(matcher.accepts(heard, track("Get Lucky", isrc = "GBDUW0000059")))
    }

    @Test
    fun `an agreeing isrc outranks everything`() {
        val chosen = matcher.best(
            heard,
            listOf(
                track("Get Lucky", album = "Random Access Memories", id = "plain"),
                track("Get Lucky", album = "Greatest Hits", isrc = "USQX91300108", id = "exact"),
            ),
        )
        assertEquals("exact", chosen?.source?.id)
    }

    @Test
    fun `a row with no artist credit is never a match`() {
        assertNull(matcher.best(heard, listOf(track("Get Lucky", artists = emptyList()))))
    }

    @Test
    fun `the chosen row keeps its provider identity and carries no stream`() {
        val chosen = matcher.best(heard, listOf(track("Get Lucky")))!!
        assertEquals("deezer", chosen.source.provider)
        assertEquals("1", chosen.source.id)
        assertTrue(chosen.streamCandidates.isEmpty())
    }
}
