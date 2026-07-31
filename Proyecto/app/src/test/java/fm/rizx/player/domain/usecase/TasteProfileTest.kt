package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.Daypart
import fm.rizx.player.domain.model.PlayStat
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SoundGenre
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TasteProfileTest {

    /** A fixed "now", so every fixture's dates mean something exact. */
    private val now = Instant.parse("2026-07-30T12:00:00Z").toEpochMilli()

    // ---- The clock ------------------------------------------------------------------------------

    @Test
    fun `a play from this morning outweighs one from last month`() {
        val profile = TasteProfile(
            listOf(
                stat(track("Old"), at = "2026-06-25T12:00:00Z"),
                stat(track("Fresh"), at = "2026-07-30T08:00:00Z"),
            ),
            nowMs = now,
        )

        assertEquals(listOf("Fresh", "Old"), profile.ranked.map { it.title })
    }

    @Test
    fun `the decay halves the weight every three days`() {
        val profile = TasteProfile(
            listOf(
                stat(track("Now"), at = "2026-07-30T12:00:00Z"),
                stat(track("Three days"), at = "2026-07-27T12:00:00Z"),
            ),
            nowMs = now,
        )

        val fresh = profile.weightOf(ProviderRef("deezer", "Now"))
        val older = profile.weightOf(ProviderRef("deezer", "Three days"))
        assertEquals(fresh / 2f, older, 0.01f)
    }

    @Test
    fun `a play in the future is not weighted above the present`() {
        // A device whose clock moved backwards must not make one song permanently top of the list.
        val profile = TasteProfile(
            listOf(
                stat(track("Tomorrow"), at = "2026-07-31T12:00:00Z"),
                stat(track("Now"), at = "2026-07-30T12:00:00Z"),
            ),
            nowMs = now,
        )

        assertEquals(
            profile.weightOf(ProviderRef("deezer", "Now")),
            profile.weightOf(ProviderRef("deezer", "Tomorrow")),
            0.001f,
        )
    }

    @Test
    fun `with no timestamps at all, position in the list is the clock again`() {
        // Rows written before the log existed. Treating them as infinitely old would zero the taste.
        val stats = List(10) { PlayStat(track("Played $it")) }

        val profile = TasteProfile(stats, nowMs = now)

        assertEquals("Played 0", profile.ranked.first().title)
        assertTrue(profile.weightOf(ProviderRef("deezer", "Played 0")) > 0f)
        assertNull(profile.ageDays(stats.first()))
    }

    // ---- Retention ------------------------------------------------------------------------------

    @Test
    fun `a song mostly skipped falls below one mostly heard out`() {
        val played = "2026-07-30T11:00:00Z"
        val profile = TasteProfile(
            listOf(
                stat(track("Skipped"), at = played, plays = 4, completions = 1, skips = 3),
                stat(track("Heard"), at = played, plays = 4, completions = 4),
            ),
            nowMs = now,
        )

        assertEquals(listOf("Heard", "Skipped"), profile.ranked.map { it.title })
    }

    @Test
    fun `a song with no outcome recorded sits between the two, because we do not know`() {
        val played = "2026-07-30T11:00:00Z"
        val profile = TasteProfile(
            listOf(
                stat(track("Skipped"), at = played, plays = 2, skips = 2),
                stat(track("Unknown"), at = played, plays = 2),
                stat(track("Heard"), at = played, plays = 2, completions = 2),
            ),
            nowMs = now,
        )

        assertEquals(listOf("Heard", "Unknown", "Skipped"), profile.ranked.map { it.title })
    }

    @Test
    fun `replays raise a song, with diminishing returns`() {
        val played = "2026-07-30T11:00:00Z"
        val profile = TasteProfile(
            listOf(
                stat(track("Once"), at = played, plays = 1),
                stat(track("Twenty"), at = played, plays = 20),
            ),
            nowMs = now,
        )

        val once = profile.weightOf(ProviderRef("deezer", "Once"))
        val twenty = profile.weightOf(ProviderRef("deezer", "Twenty"))
        assertTrue(twenty > once)
        assertTrue("twenty plays is not twenty times the taste", twenty < once * 3f)
    }

    @Test
    fun `music played at this hour is nudged up`() {
        val played = "2026-07-30T11:00:00Z"
        val morning = PlayStat(track("Morning"), plays = 2, lastPlayedAtIso = played, dayparts = listOf(0, 2, 0, 0))
        val night = PlayStat(track("Night"), plays = 2, lastPlayedAtIso = played, dayparts = listOf(2, 0, 0, 0))

        val atBreakfast = TasteProfile(listOf(night, morning), nowMs = now, daypart = Daypart.MORNING)

        assertEquals(listOf("Morning", "Night"), atBreakfast.ranked.map { it.title })
        // Without a daypart the two are indistinguishable, so input order stands.
        assertEquals(listOf("Night", "Morning"), TasteProfile(listOf(night, morning), nowMs = now).ranked.map { it.title })
    }

    // ---- Artists --------------------------------------------------------------------------------

    @Test
    fun `a channel name and the artist behind it are one artist, under the shorter name`() {
        val profile = TasteProfile(
            listOf(
                PlayStat(track("Levitating", artist = "DualipaVEVO", id = "yt1")),
                PlayStat(track("Houdini", artist = "Dua Lipa", id = "d1")),
            ),
            nowMs = now,
        )

        assertEquals(1, profile.artistCount)
        assertEquals("Dua Lipa", profile.artists.single().name)
        assertEquals(2, profile.artists.single().plays)
    }

    @Test
    fun `a liked song counts even when it was never played`() {
        val liked = track("Liked")

        val profile = TasteProfile(emptyList(), liked = listOf(liked), nowMs = now)

        assertTrue(profile.knows(liked))
        assertTrue("it was never played, so it is not in the log", !profile.hasPlayed(liked))
    }

    // ---- Clusters -------------------------------------------------------------------------------

    @Test
    fun `two groups never played together are two facets`() {
        val profile = TasteProfile(
            List(4) { stat(track("C$it", artist = "Fuerza Regida", id = "c$it"), at = "2026-07-20T21:0$it:00Z") } +
                List(4) { stat(track("H$it", artist = "Daft Punk", id = "h$it"), at = "2026-07-13T10:0$it:00Z") },
            nowMs = now,
        )

        val clusters = profile.clusters()

        assertEquals(2, clusters.size)
        assertEquals(listOf("Fuerza Regida", "Daft Punk"), clusters.map { it.lead })
        assertEquals(listOf(0, 1), clusters.map { it.index })
    }

    @Test
    fun `artists played in the same sitting are one facet`() {
        val profile = TasteProfile(
            List(4) { stat(track("A$it", artist = "Artist A", id = "a$it"), at = "2026-07-20T21:0$it:00Z") } +
                List(4) { stat(track("B$it", artist = "Artist B", id = "b$it"), at = "2026-07-20T21:1$it:00Z") },
            nowMs = now,
        )

        val clusters = profile.clusters()

        assertEquals(1, clusters.size)
        // Folded keys: ArtistNameMatching strips spacing and case, so two spellings meet.
        assertEquals(setOf("artista", "artistb"), clusters.single().artistKeys)
    }

    @Test
    fun `a shared genre keeps two artists together even with no sitting in common`() {
        val profile = TasteProfile(
            List(4) { stat(track("A$it", artist = "Artist A", id = "a$it", tags = listOf("Reggaeton")), at = "2026-07-20T21:0$it:00Z") } +
                List(4) { stat(track("B$it", artist = "Artist B", id = "b$it", tags = listOf("Urbano latino")), at = "2026-07-13T10:0$it:00Z") },
            nowMs = now,
        )

        val clusters = profile.clusters()

        assertEquals(1, clusters.size)
        assertEquals(SoundGenre.REGGAETON, clusters.single().genre)
    }

    @Test
    fun `there are never more than three facets, and nobody is left out of them`() {
        // Five artists, each in their own sitting a week apart — as split as a taste can be.
        val stats = (0 until 5).flatMap { a ->
            List(3) { i -> stat(track("T$a$i", artist = "Artist $a", id = "t$a$i"), at = "2026-07-${10 + a}T10:0$i:00Z") }
        }

        val clusters = TasteProfile(stats, nowMs = now).clusters(max = 3)

        assertEquals(3, clusters.size)
        val placed = clusters.flatMap { it.artistKeys }.toSet()
        assertEquals("every artist must live in some facet", 5, placed.size)
    }

    @Test
    fun `a facet is labelled by its strongest artists`() {
        val profile = TasteProfile(
            List(6) { stat(track("A$it", artist = "Artist A", id = "a$it"), at = "2026-07-20T21:0$it:00Z") } +
                List(2) { stat(track("B$it", artist = "Artist B", id = "b$it"), at = "2026-07-20T21:1$it:00Z") },
            nowMs = now,
        )

        val cluster = profile.clusters().single()

        assertEquals("Artist A", cluster.lead)
        assertEquals("Artist A, Artist B", cluster.label)
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private fun stat(
        track: Track,
        at: String,
        plays: Int = 1,
        completions: Int = 0,
        skips: Int = 0,
    ) = PlayStat(track, plays = plays, completions = completions, skips = skips, lastPlayedAtIso = at)

    private fun track(
        title: String,
        artist: String = "Someone $title",
        id: String = title,
        tags: List<String> = emptyList(),
    ) = Track(title, artists = listOf(ArtistCredit(artist)), tags = tags, source = ProviderRef("deezer", id))
}
