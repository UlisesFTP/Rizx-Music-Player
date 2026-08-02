package fm.rizx.player.domain.canvas

import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether a video really belongs to the song.
 *
 * Before this existed the canvas played the first search hit that happened to be a video, which is how a
 * slowed edit or somebody's reaction upload ends up filling the screen behind the album version.
 */
class CanvasTrackMatcherTest {

    private fun track(
        title: String = "Levitating",
        artist: String? = "Dua Lipa",
        durationMs: Long? = 203_000,
    ) = Track(
        title = title,
        artists = listOfNotNull(artist?.let { ArtistCredit(it) }),
        durationMs = durationMs,
        source = ProviderRef("deezer", "1"),
    )

    private fun target(
        title: String = "Levitating",
        artist: String? = "Dua Lipa",
        durationMs: Long? = 203_000,
    ) = CanvasMatchTarget(title, artist, durationMs)

    // ---- the version gate ----

    @Test
    fun `a remix is not the album version, however well everything else lines up`() {
        assertNull(CanvasTrackMatcher.score(track(), target(title = "Levitating (Remix)")))
    }

    @Test
    fun `and the reverse — playing the remix must not pull the original's video`() {
        assertNull(CanvasTrackMatcher.score(track(title = "Levitating (Remix)"), target()))
    }

    @Test
    fun `sped up and slowed edits are rejected, which duration alone never managed`() {
        // These are the ones that beat a duration-only match: an edit is close enough in length to win.
        assertNull(CanvasTrackMatcher.score(track(), target(title = "Levitating (Sped Up)")))
        assertNull(CanvasTrackMatcher.score(track(), target(title = "Levitating - slowed + reverb")))
    }

    @Test
    fun `a live take is rejected, but a song called Live and Let Die is not`() {
        assertNull(CanvasTrackMatcher.score(track(), target(title = "Levitating (Live at the BRITs)")))

        val wings = track(title = "Live and Let Die", artist = "Wings", durationMs = 193_000)
        assertNotNull(
            CanvasTrackMatcher.score(wings, CanvasMatchTarget("Live and Let Die", "Wings", 193_000)),
        )
    }

    @Test
    fun `the same remaster on both sides still matches`() {
        val remaster = track(title = "Yellow - 2000 Remaster")
        assertNotNull(CanvasTrackMatcher.score(remaster, target(title = "Yellow (2000 Remaster)")))
    }

    // ---- scoring ----

    @Test
    fun `a perfect match scores full marks`() {
        assertEquals(100, CanvasTrackMatcher.score(track(), target()))
    }

    @Test
    fun `a music video's intro and credits cost nothing worth mentioning`() {
        // The official video is 30s longer than the track — that is the normal case, not a warning.
        val score = CanvasTrackMatcher.score(track(), target(durationMs = 233_000))!!
        assertTrue("a 30s intro should not sink an otherwise exact match, got $score", score >= 90)
    }

    @Test
    fun `a VEVO channel counts as the artist`() {
        // "DualipaVEVO" is what YouTube actually credits; no catalogue knows that name.
        assertEquals(100, CanvasTrackMatcher.score(track(), target(artist = "DualipaVEVO")))
        assertEquals(100, CanvasTrackMatcher.score(track(), target(artist = "Dua Lipa - Topic")))
    }

    @Test
    fun `a different song with the right length is rejected`() {
        val score = CanvasTrackMatcher.score(track(), target(title = "Physical"))!!
        assertTrue("a wrong title must fall below the threshold, got $score", score < CanvasTrackMatcher.CORROBORATE)
    }

    @Test
    fun `somebody else's re-upload is rejected`() {
        val score = CanvasTrackMatcher.score(track(), target(artist = "Best Hits 2024"))!!
        assertTrue("an unrelated channel must not pass, got $score", score < CanvasTrackMatcher.CORROBORATE)
    }

    @Test
    fun `an unknown artist is not held against a candidate`() {
        // The artist is the signal we are least likely to have; punishing its absence rejects everything
        // a bare upload offers, which would leave the feature showing nothing far too often.
        assertEquals(100, CanvasTrackMatcher.score(track(), target(artist = null)))
        assertEquals(100, CanvasTrackMatcher.score(track(artist = null), target()))
    }

    // ---- the thresholds ----

    @Test
    fun `the middle band needs the title and the artist to have agreed`() {
        assertTrue(CanvasTrackMatcher.accepts(95, corroborated = false))
        assertTrue(CanvasTrackMatcher.accepts(85, corroborated = true))
        assertTrue("a length-only deduction must not need corroboration at 90", CanvasTrackMatcher.accepts(90, false))
        assertTrue(!CanvasTrackMatcher.accepts(85, corroborated = false))
        assertTrue(!CanvasTrackMatcher.accepts(79, corroborated = true))
    }

    // ---- picking ----

    @Test
    fun `the best acceptable hit wins, and the unacceptable ones are skipped entirely`() {
        val hits = listOf(
            target(title = "Levitating (Sped Up)"),   // rejected: version
            target(title = "Physical"),                // rejected: different song
            target(durationMs = 260_000),              // acceptable, long
            target(),                                  // perfect
        )

        val best = CanvasTrackMatcher.bestOf(track(), hits) { it }

        assertEquals(203_000L, best?.value?.durationMs)
        assertEquals(100, best?.score)
    }

    @Test
    fun `nothing acceptable means no canvas, not the least-bad guess`() {
        val hits = listOf(target(title = "Physical"), target(title = "Levitating (Remix)"))

        assertNull(CanvasTrackMatcher.bestOf(track(), hits) { it })
    }

    @Test
    fun `rankAll returns every acceptable hit, best first`() {
        val hits = listOf(
            target(durationMs = 260_000),              // acceptable, long
            target(title = "Physical"),                // rejected
            target(),                                  // perfect
        )

        val ranked = CanvasTrackMatcher.rankAll(track(), hits) { it }

        assertEquals("the rejected one is gone", 2, ranked.size)
        assertEquals(100, ranked[0].score)
        assertTrue("and the weaker one is still there, behind it", ranked[1].score < 100)
    }

    @Test
    fun `equal scores keep search order, so the more relevant hit stays ahead`() {
        val first = target(durationMs = 260_000)
        val second = target(durationMs = 261_000)

        val ranked = CanvasTrackMatcher.rankAll(track(), listOf(first, second)) { it }

        assertEquals(first.durationMs, ranked[0].value.durationMs)
    }

    // ---- the ambiguity rule ----

    @Test
    fun `two candidates within five points are a coin flip, not an identification`() {
        assertTrue(CanvasTrackMatcher.tooCloseToCall(100, 100))
        assertTrue(CanvasTrackMatcher.tooCloseToCall(100, 95))
    }

    @Test
    fun `a clear winner is not ambiguous`() {
        assertTrue(!CanvasTrackMatcher.tooCloseToCall(100, 94))
        assertTrue(!CanvasTrackMatcher.tooCloseToCall(95, 80))
    }
}
