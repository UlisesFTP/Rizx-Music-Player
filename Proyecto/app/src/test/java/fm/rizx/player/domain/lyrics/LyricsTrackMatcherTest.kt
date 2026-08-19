package fm.rizx.player.domain.lyrics

import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // "mix" is a version word in its own right ("Rocking Vibe Mix"), and sits inside "remix".
        assertEquals(setOf("remix", "mix"), LyricsTrackMatcher.versionTags("Yellow [Tiësto Remix]"))
        assertEquals(setOf("mix"), LyricsTrackMatcher.versionTags("DNA (Pedal 2 LA Mix)"))
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
        val far = LyricsTrackMatcher.score(ours, target("Yellow", durationMs = 230_000))!!

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
    fun `a different artist is not this recording`() {
        // Every one of these was NetEase's winning candidate for the BTS track named on the left: a
        // Chinese singer's own "2.0", a marching band's "ON", a tribute act's "Stay". They used to cost
        // a penalty and win anyway, because nothing else in the search matched better.
        assertNull(LyricsTrackMatcher.score(track("2.0", artist = "BTS", durationMs = 169_000), target("2.0", artist = "辉子", durationMs = 164_500)))
        assertNull(LyricsTrackMatcher.score(track("ON", artist = "BTS", durationMs = 246_000), target("Bts On", artist = "The Boston College Marching Band, David Healey", durationMs = 298_701)))
        assertNull(LyricsTrackMatcher.score(track("Yellow"), target("Yellow", artist = "Radiohead")))
    }

    @Test
    fun `a cover whose title names our artist is still a cover`() {
        // "BTS DNA" by 徐州鹤: their title mentions BTS, but the singer is someone else. This is the
        // tribute pattern, and it is the reverse of the one channel case that is allowed below.
        assertNull(LyricsTrackMatcher.score(track("DNA", artist = "BTS", durationMs = 223_000), target("BTS DNA", artist = "徐州鹤", durationMs = 230_191)))
        // KuGou's "friends" by an act called Friends, for BTS's "Friends".
        assertNull(LyricsTrackMatcher.score(track("Friends", artist = "BTS", durationMs = 199_000), target("friends", artist = "Friends", durationMs = 187_715)))
    }

    @Test
    fun `a channel-credited track whose title spells out the artist still matches, at a price`() {
        // A YouTube upload by the label: our credit is the channel, our title carries the artist.
        val ours = track("BTS (방탄소년단) 'DNA' Official MV", artist = "HYBE LABELS", durationMs = 223_000)
        val score = LyricsTrackMatcher.score(ours, target("DNA", artist = "BTS", durationMs = 223_000))

        assertNotNull(score)
        assertTrue("$score", score!! > 0)
    }

    @Test
    fun `a featured credit on either side is the same artist`() {
        assertEquals(0L, LyricsTrackMatcher.score(track("Who", artist = "BTS"), target("Who", artist = "BTS, Lauv")))
        assertEquals(0L, LyricsTrackMatcher.score(track("Who", artist = "BTS & Lauv"), target("Who", artist = "Lauv")))
        assertEquals(0L, LyricsTrackMatcher.score(track("Who", artist = "BTS"), target("Who", artist = "BTS (防弹少年团), Thanh Bùi")))
    }

    @Test
    fun `a placeholder credit says nothing, so it is not held against a candidate`() {
        assertEquals(0L, LyricsTrackMatcher.score(track("Yellow", artist = "Various Artists"), target("Yellow", artist = "Coldplay")))
        assertEquals(0L, LyricsTrackMatcher.score(track("Yellow", artist = "Coldplay"), target("Yellow", artist = "Unknown Artist")))
    }

    @Test
    fun `sharing a name with a later credit keeps a candidate eligible, but not certain`() {
        // A cover filed as "Ysabelle Cuevas, Bts": the original artist is on the credit line, second.
        val ours = track("FAKE LOVE", artist = "BTS", durationMs = 242_000)
        val cover = LyricsTrackMatcher.score(ours, target("Fake Love", artist = "Ysabelle Cuevas, Bts", durationMs = 248_528))

        assertNotNull(cover)
        assertTrue("$cover", cover!! >= fm.rizx.player.domain.model.Lyrics.CONFIDENT_MATCH_MS)
        // Billed first, it is simply the same artist.
        assertEquals(0L, LyricsTrackMatcher.score(track("Who", artist = "BTS"), target("Who", artist = "BTS (防弹少年团), Lauv")))
    }

    @Test
    fun `a title that is another song entirely is refused, whatever the artist`() {
        // NetEase's tenth result for "BTS IDOL" is Jung Kook's "Dreamers", credited to BTS as well.
        val ours = track("IDOL", artist = "BTS", durationMs = 222_000)
        assertNull(LyricsTrackMatcher.score(ours, target("Dreamers [Music from the FIFA World Cup]", artist = "Jung Kook, FIFA Sound, BTS", durationMs = 201_391)))
        assertNull(LyricsTrackMatcher.score(track("Stay", artist = "BTS"), target("Stay Gold", artist = "BTS")))
    }

    @Test
    fun `a channel-titled upload contains the real title, word for word`() {
        // "IDOL" is a word of the upload's title; "For You" is not a word-run of "For Youth".
        val upload = track("BTS (방탄소년단) 'IDOL' Official MV", artist = "HYBE LABELS", durationMs = 222_000)
        assertNotNull(LyricsTrackMatcher.score(upload, target("IDOL", artist = "BTS", durationMs = 222_000)))
        assertNull(LyricsTrackMatcher.score(track("For Youth", artist = "BTS"), target("FOR YOU", artist = "BTS")))
    }

    @Test
    fun `pick keeps the score bestOf throws away`() {
        val ours = track("Yellow", durationMs = 200_000)
        val picked = LyricsTrackMatcher.pick(ours, listOf(target("Yellow", durationMs = 203_000))) { it }

        assertEquals(3_000L, picked?.score)
        assertEquals(203_000L, picked?.candidate?.durationMs)
    }

    // ---- the file's own header ----

    @Test
    fun `a file whose header names a version the track is not is the wrong file`() {
        // KuGou's listing said "Dynamite — BTS, 3:19"; the file said otherwise on its first line.
        val ours = track("Dynamite", artist = "BTS", durationMs = 199_000)
        assertTrue(LyricsTrackMatcher.headerContradicts(ours, "Dynamite (EDM Remix) - BTS (防弹少年团)"))
        assertFalse(LyricsTrackMatcher.headerContradicts(ours, "Dynamite - BTS (防弹少年团)"))
        // A first line that is a lyric, brackets and all, is never held to this.
        assertFalse(LyricsTrackMatcher.headerContradicts(ours, "'Cause I-I-I'm in the stars tonight (remix it)"))
        assertFalse(LyricsTrackMatcher.headerContradicts(track("Yellow (Remix)"), "Look at the stars - look how they shine"))
    }

    // ---- the duration gate ----

    @Test
    fun `a timed lyric from a cut this much longer is a different edit`() {
        // KuGou's "Intro + 봄날" medley (400 s) for the 171 s "Intro : Persona": right artist, title
        // contained, and every one of its timings past the third minute points at nothing.
        val ours = track("Intro : Persona", artist = "BTS", durationMs = 171_000)
        assertNull(LyricsTrackMatcher.score(ours, target("Intro+봄날", artist = "BTS", durationMs = 400_326)))
    }

    @Test
    fun `but the words of a longer cut are still the right words`() {
        val ours = track("Yellow", durationMs = 200_000)
        val prose = LyricsMatchTarget(title = "Yellow", artist = "Coldplay", durationMs = 300_000, synced = false)

        assertNotNull(LyricsTrackMatcher.score(ours, prose))
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
        assertTrue(LyricsTrackMatcher.sameTitle("Trivia 轉 : Seesaw", "Seesaw"))
        assertTrue(LyricsTrackMatcher.sameTitle("Intro : Persona", "Persona"))
        assertTrue(LyricsTrackMatcher.sameTitle("Dope -超ヤベー!-", "Dope"))
        assertTrue(LyricsTrackMatcher.sameTitle("DNA - Pedal 2 LA Mix", "DNA (Pedal 2 LA Mix)"))
    }

    @Test
    fun `a title that merely starts with ours is another song`() {
        // Both are BTS songs, both were NetEase's pick for the shorter title, both are wrong.
        assertFalse(LyricsTrackMatcher.sameTitle("Stay", "Stay Gold"))
        assertFalse(LyricsTrackMatcher.sameTitle("For Youth", "FOR YOU"))
        assertFalse(LyricsTrackMatcher.sameTitle("Boy In Luv", "Boy With Luv"))
    }

    // ---- what the probe against BTS's discography turned up ----

    @Test
    fun `a language version written with dashes instead of brackets is still a version`() {
        // NetEase's own spelling for three of the Japanese re-recordings, none of them bracketed.
        assertEquals(setOf("japanese"), LyricsTrackMatcher.languageTags("N.O -Japanese Ver.-"))
        assertEquals(setOf("japanese"), LyricsTrackMatcher.languageTags("BOY IN LUV -Japanese Ver.-"))
        assertEquals(setOf("japanese"), LyricsTrackMatcher.languageTags("Danger -Japanese Ver.-"))
        // And the katakana spelling of "Japanese version".
        assertEquals(setOf("japanese"), LyricsTrackMatcher.languageTags("RUN (ジャパニーズバージョン)"))
    }

    @Test
    fun `a remix in full-width brackets is a remix`() {
        assertEquals(setOf("remix", "mix"), LyricsTrackMatcher.versionTags("BTS (防弹少年团)-Dynamite（1MA remix）"))
        assertNull(LyricsTrackMatcher.score(track("Dynamite", artist = "BTS"), target("Dynamite（1MA remix）", artist = "BTS")))
    }

    @Test
    fun `a mix is a version, so the plain recording and its mix never trade lyrics`() {
        assertNull(LyricsTrackMatcher.score(track("DNA (Pedal 2 LA Mix)", artist = "BTS", durationMs = 247_000), target("DNA", artist = "BTS", durationMs = 223_000)))
        assertNotNull(LyricsTrackMatcher.score(track("FAKE LOVE (Rocking Vibe Mix)", artist = "BTS", durationMs = 238_000), target("FAKE LOVE (Rocking Vibe Mix)", artist = "BTS (防弹少年团)", durationMs = 238_494)))
    }
}
