package fm.rizx.player.domain.lyrics

import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTrackMatcherTest {

    private fun track(title: String, artist: String = "Coldplay", durationMs: Long? = 200_000) = Track(
        title = title,
        artists = listOf(ArtistCredit(name = artist)),
        durationMs = durationMs,
        source = ProviderRef("deezer", "42"),
    )

    private fun target(title: String, artist: String = "Coldplay", durationMs: Long? = 200_000) =
        LyricsMatchTarget(title = title, artist = artist, durationMs = durationMs)

    // ---- version tags ----

    @Test
    fun `a plain title has no version tags`() {
        assertEquals(emptySet<String>(), LyricsTrackMatcher.versionTags("Yellow"))
    }

    @Test
    fun `qualifiers in brackets and after a dash are read as versions`() {
        assertEquals(setOf("live"), LyricsTrackMatcher.versionTags("Yellow (Live at Glastonbury)"))
        assertEquals(setOf("remix"), LyricsTrackMatcher.versionTags("Yellow [Tiësto Remix]"))
        assertEquals(setOf("remaster"), LyricsTrackMatcher.versionTags("Yellow - 2011 Remaster"))
        assertEquals(setOf("spedup"), LyricsTrackMatcher.versionTags("Yellow (Sped Up)"))
        assertEquals(setOf("spedup"), LyricsTrackMatcher.versionTags("Yellow (spedup)"))
    }

    @Test
    fun `a version word in the song's own name is not a version tag`() {
        // The trap: "Live and Let Die" is not a live recording, and "Remix" is not in brackets here.
        assertEquals(emptySet<String>(), LyricsTrackMatcher.versionTags("Live and Let Die"))
        assertEquals(emptySet<String>(), LyricsTrackMatcher.versionTags("The Remix Artist"))
    }

    // ---- language tags ----

    @Test
    fun `a re-recording in another language is tagged with it`() {
        // Verbatim from NetEase's answer for "BTS DNA" — all three of these outrank the Korean original.
        assertEquals(setOf("japanese"), LyricsTrackMatcher.languageTags("DNA (Japanese Version)"))
        assertEquals(setOf("japanese"), LyricsTrackMatcher.languageTags("DNA (Japanese ver.)"))
        assertEquals(setOf("japanese"), LyricsTrackMatcher.languageTags("血、汗、涙 (Japanese ver.)"))
        assertEquals(setOf("japanese"), LyricsTrackMatcher.languageTags("Spring Day - 日本語版"))
    }

    @Test
    fun `a bare language in brackets counts, because that is all the qualifier says`() {
        assertEquals(setOf("korean"), LyricsTrackMatcher.languageTags("DNA (Korean)"))
        assertEquals(setOf("spanish"), LyricsTrackMatcher.languageTags("Bailando (Español)"))
    }

    @Test
    fun `a language word inside a real title is not a language tag`() {
        // The trap this rule exists for: tagging "French Kiss" would reject the one lyric that fits it.
        assertEquals(emptySet<String>(), LyricsTrackMatcher.languageTags("French Kiss"))
        assertEquals(emptySet<String>(), LyricsTrackMatcher.languageTags("Lady Gaga - French Kiss"))
        assertEquals(emptySet<String>(), LyricsTrackMatcher.languageTags("Song (Spanish Guitar)"))
        // "Denver" contains "ver" — a version marker with no language is still no language.
        assertEquals(emptySet<String>(), LyricsTrackMatcher.languageTags("Song (Live in Denver)"))
    }

    @Test
    fun `mandarin and chinese are the same language, so they agree`() {
        assertEquals(
            LyricsTrackMatcher.languageTags("Song (Mandarin Version)"),
            LyricsTrackMatcher.languageTags("Song (Chinese Version)"),
        )
    }

    // ---- the language gate ----

    @Test
    fun `the japanese re-recording is refused for the korean original`() {
        // The bug in one assertion. Same artist, same title once the bracket is stripped, and 573 ms
        // apart — every signal but the language agrees, and the wrong one carries word timings.
        val ours = track("DNA", artist = "BTS", durationMs = 223_000)
        val japanese = target("DNA (Japanese Version)", artist = "BTS (防弹少年团)", durationMs = 223_573)

        assertNull(LyricsTrackMatcher.score(ours, japanese))
    }

    @Test
    fun `and the korean original is refused when the japanese one is what is playing`() {
        val ours = track("DNA (Japanese ver.)", artist = "BTS", durationMs = 223_573)

        assertNull(LyricsTrackMatcher.score(ours, target("DNA", artist = "BTS", durationMs = 223_000)))
        assertNotNull(LyricsTrackMatcher.score(ours, target("DNA (Japanese Version)", artist = "BTS", durationMs = 223_573)))
    }

    @Test
    fun `the language gate leaves ordinary songs alone`() {
        val ours = track("Yellow")

        assertNotNull(LyricsTrackMatcher.score(ours, target("Yellow")))
        assertNotNull(LyricsTrackMatcher.score(ours, target("Yellow (2000)")))
    }

    // ---- the version gate ----

    @Test
    fun `a live take is never accepted for the studio version`() {
        assertNull(LyricsTrackMatcher.score(track("Yellow"), target("Yellow (Live)")))
        assertNull(LyricsTrackMatcher.score(track("Yellow (Live)"), target("Yellow")))
    }

    @Test
    fun `a sped-up edit is rejected even when the duration is a perfect match`() {
        // This is exactly the case duration-only matching got wrong: an edit close enough in length to
        // beat the real thing, with words that fit and timings that don't.
        val ours = track("Blinding Lights", artist = "The Weeknd", durationMs = 200_000)
        val theirs = target("Blinding Lights (Sped Up)", artist = "The Weeknd", durationMs = 200_000)

        assertNull(LyricsTrackMatcher.score(ours, theirs))
    }

    @Test
    fun `the same remaster on both sides still matches`() {
        val score = LyricsTrackMatcher.score(
            track("Yellow - 2011 Remaster"),
            target("Yellow (Remastered)"),
        )

        assertNotNull(score)
    }

    // ---- scoring ----

    @Test
    fun `the closest duration wins between two equally valid candidates`() {
        val ours = track("Yellow", durationMs = 200_000)
        val close = LyricsTrackMatcher.score(ours, target("Yellow", durationMs = 201_000))!!
        val far = LyricsTrackMatcher.score(ours, target("Yellow", durationMs = 260_000))!!

        assertTrue("$close should beat $far", close < far)
    }

    @Test
    fun `an unknown duration costs, but is still eligible`() {
        val score = LyricsTrackMatcher.score(track("Yellow"), target("Yellow", durationMs = null))

        assertEquals(LyricsTrackMatcher.UNKNOWN_DURATION_PENALTY, score)
    }

    @Test
    fun `prose loses to timings, all else equal`() {
        val ours = track("Yellow")
        val synced = LyricsTrackMatcher.score(ours, target("Yellow"))!!
        val prose = LyricsTrackMatcher.score(
            ours,
            LyricsMatchTarget("Yellow", "Coldplay", durationMs = 200_000, synced = false),
        )!!

        assertEquals(LyricsTrackMatcher.UNSYNCED_PENALTY, prose - synced)
    }

    @Test
    fun `a YouTube channel credit still matches the artist behind it`() {
        // Tracks sourced from YouTube are credited to the uploader; no lyrics database knows "ColdplayVEVO".
        val ours = track("Yellow", artist = "ColdplayVEVO")
        val theirs = target("Yellow", artist = "Coldplay")

        assertEquals(0L, LyricsTrackMatcher.score(ours, theirs))
    }

    @Test
    fun `a different artist is punished but not rejected`() {
        val score = LyricsTrackMatcher.score(track("Yellow"), target("Yellow", artist = "Radiohead"))

        assertNotNull(score)
        assertTrue("$score", score!! > 0)
    }

    // ---- picking ----

    @Test
    fun `bestOf skips the wrong versions and takes the closest of the rest`() {
        val ours = track("Yellow", durationMs = 200_000)
        val candidates = listOf(
            target("Yellow (Live)", durationMs = 200_000),
            target("Yellow", durationMs = 240_000),
            target("Yellow", durationMs = 202_000),
        )

        val best = LyricsTrackMatcher.bestOf(ours, candidates) { it }

        assertEquals(202_000L, best?.durationMs)
    }

    @Test
    fun `bestOf returns nothing when every candidate is a different recording`() {
        val best = LyricsTrackMatcher.bestOf(
            track("Yellow"),
            listOf(target("Yellow (Live)"), target("Yellow (Karaoke)")),
        ) { it }

        assertNull(best)
    }

    @Test
    fun `a subtitle one side kept and the other dropped is still the same song`() {
        assertTrue(LyricsTrackMatcher.sameTitle("Bohemian Rhapsody", "Bohemian Rhapsody"))
        assertTrue(LyricsTrackMatcher.sameTitle("Déjà Vu", "Deja Vu"))
        assertTrue(LyricsTrackMatcher.sameTitle("Yellow", "Yellow (2000)"))
    }
}
