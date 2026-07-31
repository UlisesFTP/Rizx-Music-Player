package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.AppMix
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.MixKind
import fm.rizx.player.domain.model.PlayStat
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MixBuilderTest {

    private val builder = MixBuilder()

    // ---- Cold start ---------------------------------------------------------------------------

    @Test
    fun `with nothing to go on there are no mixes`() {
        assertTrue(wall(history = emptyList()).isEmpty())
    }

    @Test
    fun `the charts alone still give a mix, so a fresh install gets a wall`() {
        val mixes = wall(history = emptyList(), feed = chart(10))

        // Only the global one: every other kind asks something of the history.
        assertEquals(listOf(MixKind.GLOBAL), mixes.map { it.kind })
        assertEquals(10, mixes.single().tracks.size)
    }

    @Test
    fun `a chart too small to be worth playing is not made into a mix`() {
        assertTrue(wall(history = emptyList(), feed = chart(4)).isEmpty())
    }

    @Test
    fun `the global mix takes one track per source at a time`() {
        val feed = HomeFeed(
            topTracks = listOf(
                AttributedResult("deezer-dashboard", "Deezer", List(4) { track("D$it") }),
                AttributedResult("applemusic-charts", "Apple", List(4) { track("A$it") }),
            ),
        )

        val global = wall(history = emptyList(), feed = feed).single()

        assertEquals(
            listOf("D0", "A0", "D1", "A1", "D2", "A2", "D3", "A3"),
            global.tracks.map { it.title },
        )
        // Its meter is how many sources actually contributed: two of the three that would fill it.
        assertEquals(2f / 3f, global.weight, 0.001f)
    }

    // ---- The weighting ------------------------------------------------------------------------

    @Test
    fun `an explicit like outranks a more recent play`() {
        val history = List(10) { track("Played $it") }
        val liked = listOf(history.last()) // the oldest thing in the history, but liked

        val onRepeat = wall(history, liked).first { it.kind == MixKind.ON_REPEAT }

        assertEquals("Played 9", onRepeat.tracks.first().title)
        assertEquals("Played 0", onRepeat.tracks[1].title)
    }

    @Test
    fun `on repeat is ordered by how often a song was played, not by how recently`() {
        val history = List(10) { track("Played $it") }
        // A song from the bottom of the list, played five times.
        val stats = history.mapIndexed { i, t -> PlayStat(t, plays = if (i == 9) 5 else 1) }

        val onRepeat = builder.build(TasteProfile(stats)).first { it.kind == MixKind.ON_REPEAT }

        assertEquals("Played 9", onRepeat.tracks.first().title)
    }

    // ---- Daily mixes --------------------------------------------------------------------------

    @Test
    fun `a daily mix opens on the listener's own songs and keeps them at the front`() {
        val history = List(10) { track("Mine $it", artist = "Mine") }

        val daily = wall(history, feed = chart(40)).first { it.kind == MixKind.DAILY }

        assertEquals(listOf("Mine 0", "Mine 1", "Mine 2", "Mine 3"), daily.tracks.take(4).map { it.title })
        assertEquals(0, daily.index)
        assertEquals("Mine", daily.lead)
    }

    @Test
    fun `a daily mix is roughly seven parts known to three parts new`() {
        val history = List(20) { track("Mine $it", artist = "Mine") }

        val daily = wall(history, feed = chart(40)).first { it.kind == MixKind.DAILY }

        assertEquals(daily.tracks.size, daily.knownCount + daily.freshCount)
        val fresh = daily.freshCount.toFloat() / daily.tracks.size
        assertTrue("$fresh of the mix is new", fresh in 0.25f..0.35f)
        // And what it calls "new" really is: nothing by an artist this listener plays.
        val newSongs = daily.tracks.filter { it.title.startsWith("Chart") }
        assertTrue(newSongs.none { song -> song.artists.any { it.name == "Mine" } })
    }

    @Test
    fun `a facet with too few of the listener's own songs is not made into a mix`() {
        // Three plays and a big chart: the old builder called that a daily mix, but a list that is
        // seven-eighths strangers is a discovery row wearing the wrong name.
        val history = List(3) { track("Mine $it", artist = "Mine") }

        assertTrue(wall(history, feed = chart(40)).none { it.kind == MixKind.DAILY })
    }

    @Test
    fun `two groups of artists never played together become two different mixes`() {
        // Two sittings a week apart: corridos in one, house in the other.
        val corridos = List(9) { track("Corrido $it", artist = "Fuerza Regida") }
        val house = List(9) { track("House $it", artist = "Daft Punk") }
        val stats = corridos.mapIndexed { i, t -> stat(t, atIso = "2026-07-20T21:0${i}:00Z") } +
            house.mapIndexed { i, t -> stat(t, atIso = "2026-07-13T10:0${i}:00Z") }

        val daily = builder.build(TasteProfile(stats, nowMs = NOW)).filter { it.kind == MixKind.DAILY }

        assertEquals(2, daily.size)
        assertEquals(listOf("Fuerza Regida", "Daft Punk"), daily.map { it.lead })
        assertEquals(listOf("DAILY|0", "DAILY|1"), daily.map { it.id })
        // No song may be in two of them, or the wall shows the same music twice.
        assertTrue(daily[0].tracks.map { it.source }.none { it in daily[1].tracks.map { t -> t.source } })
    }

    @Test
    fun `artists played in the same sitting stay in one mix`() {
        val stats = List(9) { stat(track("A $it", artist = "Artist A"), atIso = "2026-07-20T21:0${it}:00Z") } +
            List(9) { stat(track("B $it", artist = "Artist B"), atIso = "2026-07-20T21:1${it}:00Z") }

        val daily = builder.build(TasteProfile(stats, nowMs = NOW)).filter { it.kind == MixKind.DAILY }

        assertEquals(1, daily.size)
    }

    @Test
    fun `a daily mix's identity survives its facet being recomputed`() {
        val history = List(10) { track("Mine $it", artist = "Mine") }
        val before = wall(history, feed = chart(40)).first { it.kind == MixKind.DAILY }

        // Another artist takes over the facet's name, and the day moves on.
        val after = wall(history + List(12) { track("Yours $it", artist = "Yours") }, feed = chart(40), day = 9)
            .first { it.kind == MixKind.DAILY }

        assertEquals("the tile keeps its key, so the Home is not re-laid-out", before.id, after.id)
    }

    @Test
    fun `the day rotates what a mix draws, without changing which mixes exist`() {
        val history = List(20) { track("Mine $it", artist = "Mine") }

        val monday = wall(history, feed = chart(40), day = 1)
        val friday = wall(history, feed = chart(40), day = 5)

        assertEquals(monday.map { it.id }, friday.map { it.id })
        assertTrue(
            "the same mix on two days should not be the same list",
            monday.first().tracks != friday.first().tracks,
        )
    }

    @Test
    fun `the personalized rows fill a mix but never decide that it exists`() {
        val history = List(10) { track("Mine $it", artist = "Mine") }
        val forYou = listOf(
            ForYouSection.Mix("Mine 0", List(10) { track("Rec $it", artist = "Stranger $it") }),
        )

        val without = wall(history, feed = chart(40))
        val with = wall(history, feed = chart(40), forYou = forYou)

        assertEquals("the set of mixes must not move when the slow half lands", without.map { it.id }, with.map { it.id })
        // But the recommendations do get used, ahead of chart filler.
        assertTrue(with.first { it.kind == MixKind.DAILY }.tracks.any { it.title.startsWith("Rec ") })
    }

    @Test
    fun `a mix never carries the same track twice`() {
        // The same song both played and liked, and present in the charts as well.
        val song = track("Timeless", artist = "The Weeknd")
        val history = List(9) { track("Other $it", artist = "The Weeknd") } + song
        val feed = chart(10, extra = listOf(song))

        val tracks = wall(history, liked = listOf(song), feed = feed)
            .first { it.kind == MixKind.DAILY }
            .tracks

        assertEquals(tracks.distinctBy { it.source }.size, tracks.size)
    }

    // ---- Artist mixes -------------------------------------------------------------------------

    @Test
    fun `an artist played twice with enough songs gets their own mix`() {
        val history = List(2) { track("Bunny $it", artist = "Bad Bunny") }
        val feed = chart(10, extra = List(3) { track("Chart bunny $it", artist = "Bad Bunny") })

        val artistMix = wall(history, feed = feed).firstOrNull { it.kind == MixKind.ARTIST }

        assertNotNull(artistMix)
        assertEquals("Bad Bunny", artistMix!!.subject)
        assertEquals(5, artistMix.tracks.size)
        assertEquals(1, artistMix.artistCount)
    }

    @Test
    fun `one play is not a taste`() {
        val history = listOf(track("Bunny", artist = "Bad Bunny"))
        val feed = chart(10, extra = List(4) { track("Chart bunny $it", artist = "Bad Bunny") })

        assertTrue(wall(history, feed = feed).none { it.kind == MixKind.ARTIST })
    }

    @Test
    fun `an artist with too few songs gets no mix rather than a thin one`() {
        val history = List(2) { track("Bunny $it", artist = "Bad Bunny") }

        assertTrue(wall(history, feed = chart(10)).none { it.kind == MixKind.ARTIST })
    }

    @Test
    fun `the artist mix is never about someone who already leads a daily mix`() {
        val history = List(12) { track("Mine $it", artist = "Mine") }
        val feed = chart(20, extra = List(5) { track("Chart mine $it", artist = "Mine") })

        val mixes = wall(history, feed = feed)

        assertEquals("Mine", mixes.first { it.kind == MixKind.DAILY }.lead)
        assertTrue(mixes.none { it.kind == MixKind.ARTIST && it.subject == "Mine" })
    }

    @Test
    fun `a channel name and the artist behind it are the same artist, and the clean name wins`() {
        // A play that came out of a YouTube Mix is credited to the uploader. Without folding, the
        // history of anyone who plays from search splinters into channel-shaped strangers.
        val history = listOf(
            track("Levitating", artist = "DualipaVEVO", id = "yt1"),
            track("Houdini", artist = "Dua Lipa", id = "d1"),
        )
        val feed = chart(10, extra = List(3) { track("Chart lipa $it", artist = "Dua Lipa") })

        val artistMix = wall(history, feed = feed).first { it.kind == MixKind.ARTIST }

        assertEquals("Dua Lipa", artistMix.subject)
        assertEquals(5, artistMix.tracks.size)
    }

    // ---- Rediscover / discovery ----------------------------------------------------------------

    @Test
    fun `rediscover is what you have not heard in weeks, and only what you played more than once`() {
        val old = List(6) { track("Old $it", artist = "Artist $it") }
        val fresh = List(6) { track("New $it", artist = "Artist $it") }
        val stats = fresh.map { stat(it, atIso = "2026-07-29T12:00:00Z") } + // yesterday
            old.map { stat(it, atIso = "2026-05-01T12:00:00Z") } // three months ago

        val rediscover = builder.build(TasteProfile(stats, nowMs = NOW)).first { it.kind == MixKind.REDISCOVER }

        assertEquals(old.map { it.title }, rediscover.tracks.map { it.title })
        assertEquals("half of the history is worth reviving", 0.5f, rediscover.weight, 0.001f)
    }

    @Test
    fun `with no timestamps at all, rediscover falls back to the older half of the list`() {
        // Rows written before the listening log existed: position is the only clock there is.
        val artists = List(6) { "Artist $it" }
        val history = artists.map { track("New $it", artist = it) } + artists.map { track("Old $it", artist = it) }

        val rediscover = wall(history).first { it.kind == MixKind.REDISCOVER }

        assertEquals(artists.map { "Old $it" }, rediscover.tracks.map { it.title })
    }

    @Test
    fun `a history too short to have an older half yields no rediscover`() {
        val history = List(6) { track("Played $it", artist = "Artist ${it % 3}") }

        assertTrue(wall(history).none { it.kind == MixKind.REDISCOVER })
    }

    @Test
    fun `new-to-you only holds artists with no play at all`() {
        val history = List(10) { track("Mine $it", artist = "Mine") }
        val feed = chart(40, extra = List(2) { track("More mine $it", artist = "Mine") })

        val mixes = wall(history, feed = feed)
        val discovery = mixes.first { it.kind == MixKind.DISCOVERY }

        assertTrue(discovery.tracks.none { it.artists.any { credit -> credit.name == "Mine" } })
        // And nothing the daily mix already used, so the two aren't the same wall twice.
        val daily = mixes.first { it.kind == MixKind.DAILY }
        assertTrue(discovery.tracks.none { it.source in daily.tracks.map { d -> d.source } })
    }

    @Test
    fun `with no history there is no new-to-you, because that would just be the charts`() {
        val mixes = wall(history = emptyList(), feed = chart(40))

        assertTrue(mixes.none { it.kind == MixKind.DISCOVERY })
        assertTrue(mixes.any { it.kind == MixKind.GLOBAL })
    }

    // ---- Shape --------------------------------------------------------------------------------

    @Test
    fun `the same inputs always build the same wall`() {
        val history = List(12) { track("Played $it", artist = "Artist ${it % 4}") }
        val feed = chart(30)

        assertEquals(wall(history, feed = feed), wall(history, feed = feed))
    }

    @Test
    fun `the wall is capped, strongest kind first`() {
        val history = List(20) { track("Played $it", artist = "Artist ${it % 3}") }
        val feed = chart(40)

        val mixes = wall(history, feed = feed, limit = 3)

        assertEquals(3, mixes.size)
        assertEquals(MixKind.DAILY, mixes.first().kind)
    }

    @Test
    fun `every mix carries a meter between zero and one`() {
        val history = List(20) { track("Played $it", artist = "Artist ${it % 3}") }

        wall(history, feed = chart(40)).forEach { mix: AppMix ->
            assertTrue("${mix.kind} = ${mix.weight}", mix.weight in 0f..1f)
        }
    }

    // ---- The day's pick -----------------------------------------------------------------------

    @Test
    fun `the pick is an unheard artist, and it names the song that earned it`() {
        val history = listOf(track("Timeless", artist = "The Weeknd"))
        val forYou = listOf(
            ForYouSection.Mix(
                seedTitle = "Timeless",
                items = listOf(
                    track("Blinding Lights", artist = "The Weeknd"), // already known — skipped
                    track("Nights", artist = "Frank Ocean"),
                ),
            ),
        )

        val pick = builder.pick(profile(history), forYou)

        assertEquals("Nights", pick?.track?.title)
        assertEquals("Timeless", pick?.becauseOf)
    }

    @Test
    fun `a song-seeded row is preferred over an artist-seeded one, for the sharper reason`() {
        val forYou = listOf(
            ForYouSection.BecauseYouLike("Rosalía", listOf(track("Malamente"))),
            ForYouSection.Mix("Motomami", listOf(track("Saoko"))),
        )

        assertEquals("Motomami", builder.pick(profile(emptyList()), forYou)?.becauseOf)
    }

    @Test
    fun `with everything already familiar the top recommendation still shows`() {
        val history = listOf(track("Timeless", artist = "The Weeknd"))
        val forYou = listOf(ForYouSection.Mix("Timeless", listOf(track("Starboy", artist = "The Weeknd"))))

        assertEquals("Starboy", builder.pick(profile(history), forYou)?.track?.title)
    }

    @Test
    fun `no recommendations means no pick, rather than an invented one`() {
        assertNull(builder.pick(profile(List(5) { track("Played $it") }), emptyList()))
        assertNull(builder.pick(profile(emptyList()), listOf(ForYouSection.Mix("Seed", emptyList()))))
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /** A fixed "now", so a fixture's dates mean something exact. */
    private val NOW = Instant.parse("2026-07-30T12:00:00Z").toEpochMilli()

    /** The wall, from a plain list of plays — the shape most of these cases care about. */
    private fun wall(
        history: List<Track>,
        liked: List<Track> = emptyList(),
        feed: HomeFeed = HomeFeed(),
        forYou: List<ForYouSection> = emptyList(),
        day: Long = 0L,
        limit: Int = 7,
    ) = builder.build(profile(history, liked), feed, forYou, day, limit)

    private fun profile(history: List<Track>, liked: List<Track> = emptyList()) =
        TasteProfile(history.map { PlayStat(it) }, liked, nowMs = NOW)

    private fun stat(track: Track, atIso: String, plays: Int = 2) =
        PlayStat(track, plays = plays, lastPlayedAtIso = atIso)

    private fun track(
        title: String,
        artist: String = "Someone $title",
        id: String = title,
    ) = Track(title, artists = listOf(ArtistCredit(artist)), source = ProviderRef("deezer", id))

    /** A single-source chart of [count] tracks by distinct artists, plus anything [extra]. */
    private fun chart(count: Int, extra: List<Track> = emptyList()) = HomeFeed(
        topTracks = listOf(
            AttributedResult("deezer-dashboard", "Deezer", List(count) { track("Chart $it") } + extra),
        ),
    )
}
