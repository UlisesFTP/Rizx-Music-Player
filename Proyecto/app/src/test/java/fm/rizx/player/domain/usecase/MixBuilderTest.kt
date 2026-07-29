package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.AppMix
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.MixKind
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixBuilderTest {

    private val builder = MixBuilder()

    // ---- Cold start ---------------------------------------------------------------------------

    @Test
    fun `with nothing to go on there are no mixes`() {
        assertTrue(builder.build(history = emptyList()).isEmpty())
    }

    @Test
    fun `the charts alone still give a mix, so a fresh install gets a wall`() {
        val mixes = builder.build(history = emptyList(), feed = chart(10))

        // Only the global one: every other kind asks something of the history.
        assertEquals(listOf(MixKind.GLOBAL), mixes.map { it.kind })
        assertEquals(10, mixes.single().tracks.size)
    }

    @Test
    fun `a chart too small to be worth playing is not made into a mix`() {
        assertTrue(builder.build(history = emptyList(), feed = chart(4)).isEmpty())
    }

    @Test
    fun `the global mix takes one track per source at a time`() {
        val feed = HomeFeed(
            topTracks = listOf(
                AttributedResult("deezer-dashboard", "Deezer", List(4) { track("D$it") }),
                AttributedResult("applemusic-charts", "Apple", List(4) { track("A$it") }),
            ),
        )

        val global = builder.build(history = emptyList(), feed = feed).single()

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

        val onRepeat = builder.build(history, liked).first { it.kind == MixKind.ON_REPEAT }

        assertEquals("Played 9", onRepeat.tracks.first().title)
        assertEquals("Played 0", onRepeat.tracks[1].title)
    }

    @Test
    fun `the daily mix opens on the listener's own strongest track`() {
        val history = List(3) { track("Mine $it", artist = "Mine") }

        val daily = builder.build(history, feed = chart(10)).first { it.kind == MixKind.DAILY }

        assertEquals("Mine 0", daily.tracks.first().title)
        assertTrue("all of their own tracks belong in it", daily.tracks.map { it.title }.containsAll(history.map { it.title }))
        assertEquals(13, daily.tracks.size)
        assertEquals(1, daily.artistCount)
    }

    @Test
    fun `a mix never carries the same track twice`() {
        // The same song both played and liked, and present in the charts as well.
        val song = track("Timeless", artist = "The Weeknd")
        val history = List(3) { track("Other $it") } + song
        val feed = chart(10, extra = listOf(song))

        val tracks = builder.build(history, liked = listOf(song), feed = feed)
            .first { it.kind == MixKind.DAILY }
            .tracks

        assertEquals(tracks.distinctBy { it.source }.size, tracks.size)
    }

    // ---- Artist mixes -------------------------------------------------------------------------

    @Test
    fun `an artist played twice with enough songs gets their own mix`() {
        val history = List(2) { track("Bunny $it", artist = "Bad Bunny") }
        val feed = chart(10, extra = List(3) { track("Chart bunny $it", artist = "Bad Bunny") })

        val artistMix = builder.build(history, feed = feed).firstOrNull { it.kind == MixKind.ARTIST }

        assertNotNull(artistMix)
        assertEquals("Bad Bunny", artistMix!!.subject)
        assertEquals(5, artistMix.tracks.size)
        assertEquals(1, artistMix.artistCount)
    }

    @Test
    fun `one play is not a taste`() {
        val history = listOf(track("Bunny", artist = "Bad Bunny"))
        val feed = chart(10, extra = List(4) { track("Chart bunny $it", artist = "Bad Bunny") })

        assertTrue(builder.build(history, feed = feed).none { it.kind == MixKind.ARTIST })
    }

    @Test
    fun `an artist with too few songs gets no mix rather than a thin one`() {
        val history = List(2) { track("Bunny $it", artist = "Bad Bunny") }

        assertTrue(builder.build(history, feed = chart(10)).none { it.kind == MixKind.ARTIST })
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

        val artistMix = builder.build(history, feed = feed).first { it.kind == MixKind.ARTIST }

        assertEquals("Dua Lipa", artistMix.subject)
        assertEquals(5, artistMix.tracks.size)
    }

    // ---- Rediscover / discovery ----------------------------------------------------------------

    @Test
    fun `rediscover takes the older half, and only artists played more than once`() {
        // Twelve plays: six artists, each played once recently and once further back.
        val artists = List(6) { "Artist $it" }
        val history = artists.map { track("New $it", artist = it) } + artists.map { track("Old $it", artist = it) }

        val rediscover = builder.build(history).first { it.kind == MixKind.REDISCOVER }

        assertEquals(artists.map { "Old $it" }, rediscover.tracks.map { it.title })
        assertEquals("half of the history is worth reviving", 0.5f, rediscover.weight, 0.001f)
    }

    @Test
    fun `a history too short to have an older half yields no rediscover`() {
        val history = List(6) { track("Played $it", artist = "Artist ${it % 3}") }

        assertTrue(builder.build(history).none { it.kind == MixKind.REDISCOVER })
    }

    @Test
    fun `new-to-you only holds artists with no play at all`() {
        val history = List(3) { track("Mine $it", artist = "Mine") }
        val feed = chart(40, extra = List(2) { track("More mine $it", artist = "Mine") })

        val discovery = builder.build(history, feed = feed).first { it.kind == MixKind.DISCOVERY }

        assertTrue(discovery.tracks.none { it.artists.any { credit -> credit.name == "Mine" } })
        // And nothing the daily mix already used, so the two aren't the same wall twice.
        val daily = builder.build(history, feed = feed).first { it.kind == MixKind.DAILY }
        assertTrue(discovery.tracks.none { it.source in daily.tracks.map { d -> d.source } })
    }

    @Test
    fun `with no history there is no new-to-you, because that would just be the charts`() {
        val mixes = builder.build(history = emptyList(), feed = chart(40))

        assertTrue(mixes.none { it.kind == MixKind.DISCOVERY })
        assertTrue(mixes.any { it.kind == MixKind.GLOBAL })
    }

    // ---- Shape --------------------------------------------------------------------------------

    @Test
    fun `the same inputs always build the same wall`() {
        val history = List(12) { track("Played $it", artist = "Artist ${it % 4}") }
        val feed = chart(30)

        val first = builder.build(history, feed = feed)
        val second = builder.build(history, feed = feed)

        assertEquals(first, second)
    }

    @Test
    fun `the wall is capped, strongest kind first`() {
        val history = List(20) { track("Played $it", artist = "Artist ${it % 3}") }
        val feed = chart(40)

        val mixes = builder.build(history, feed = feed, limit = 3)

        assertEquals(3, mixes.size)
        assertEquals(MixKind.DAILY, mixes.first().kind)
    }

    @Test
    fun `every mix carries a meter between zero and one`() {
        val history = List(20) { track("Played $it", artist = "Artist ${it % 3}") }

        builder.build(history, feed = chart(40)).forEach { mix: AppMix ->
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

        val pick = builder.pick(history, forYou = forYou)

        assertEquals("Nights", pick?.track?.title)
        assertEquals("Timeless", pick?.becauseOf)
    }

    @Test
    fun `a song-seeded row is preferred over an artist-seeded one, for the sharper reason`() {
        val forYou = listOf(
            ForYouSection.BecauseYouLike("Rosalía", listOf(track("Malamente"))),
            ForYouSection.Mix("Motomami", listOf(track("Saoko"))),
        )

        assertEquals("Motomami", builder.pick(emptyList(), forYou = forYou)?.becauseOf)
    }

    @Test
    fun `with everything already familiar the top recommendation still shows`() {
        val history = listOf(track("Timeless", artist = "The Weeknd"))
        val forYou = listOf(ForYouSection.Mix("Timeless", listOf(track("Starboy", artist = "The Weeknd"))))

        assertEquals("Starboy", builder.pick(history, forYou = forYou)?.track?.title)
    }

    @Test
    fun `no recommendations means no pick, rather than an invented one`() {
        assertNull(builder.pick(List(5) { track("Played $it") }, forYou = emptyList()))
        assertNull(builder.pick(emptyList(), forYou = listOf(ForYouSection.Mix("Seed", emptyList()))))
    }

    // ---- Helpers ------------------------------------------------------------------------------

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
