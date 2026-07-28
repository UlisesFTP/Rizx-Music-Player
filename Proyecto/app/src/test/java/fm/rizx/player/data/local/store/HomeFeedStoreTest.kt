package fm.rizx.player.data.local.store

import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class HomeFeedStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private var now: Instant = Instant.parse("2026-07-24T12:00:00Z")

    private fun store() = HomeFeedStore(File(temp.root, "home_feed.json"), now = { now })

    private fun track(id: String, streamUrl: String? = null) = Track(
        title = "Song $id",
        source = ProviderRef("deezer", id),
        streamCandidates = streamUrl
            ?.let {
                listOf(
                    StreamCandidate(
                        id = id,
                        title = "Song $id",
                        source = ProviderRef("youtube", id),
                        stream = Stream(
                            url = it,
                            protocol = StreamProtocol.HTTPS,
                            source = ProviderRef("youtube", id),
                        ),
                    ),
                )
            }
            .orEmpty(),
    )

    private fun feed(vararg ids: String) = HomeFeed(
        topTracks = listOf(AttributedResult("blended", "Rizx", ids.map { track(it) })),
    )

    @Test
    fun `an empty store has nothing to serve`() = runBlocking {
        assertNull(store().read())
    }

    @Test
    fun `the feed and the personalized rows survive a round trip`() = runBlocking {
        val rows = listOf(
            ForYouSection.Mix(seedTitle = "Yellow", items = listOf(track("9"))),
            ForYouSection.ArtistsForYou(listOf(ArtistRef(name = "Coldplay", source = ProviderRef("deezer", "a1")))),
        )
        val subject = store()

        subject.write(feed("1", "2"), rows)
        val cached = subject.read()

        assertEquals(listOf("Song 1", "Song 2"), cached?.feed?.topTracks?.single()?.items?.map { it.title })
        assertEquals(2, cached?.sections?.size)
        assertTrue(cached?.sections?.first() is ForYouSection.Mix)
        assertTrue(cached?.sections?.last() is ForYouSection.ArtistsForYou)
    }

    @Test
    fun `resolved stream urls are never written to disk`() = runBlocking {
        // They are ephemeral by contract — a cached feed must carry identity and artwork, nothing playable.
        val subject = store()

        subject.write(
            HomeFeed(topTracks = listOf(AttributedResult("d", "Deezer", listOf(track("1", streamUrl = "https://cdn/x.m4a"))))),
            listOf(ForYouSection.Mix("seed", listOf(track("2", streamUrl = "https://cdn/y.m4a")))),
        )

        val raw = File(temp.root, "home_feed.json").readText()
        assertFalse("a stream url leaked into the cache", raw.contains("cdn"))
        assertTrue(subject.read()?.feed?.topTracks?.single()?.items?.single()?.streamCandidates.isNullOrEmpty())
    }

    @Test
    fun `a fresh cache is not stale, a half-hour-old one is`() = runBlocking {
        val subject = store()
        subject.write(feed("1"), emptyList())

        val fresh = subject.read()!!
        assertFalse(subject.isStale(fresh))

        now = now.plusSeconds(31 * 60)
        assertTrue(subject.isStale(subject.read()!!))
    }

    @Test
    fun `a cache older than a week is discarded rather than shown`() = runBlocking {
        val subject = store()
        subject.write(feed("1"), emptyList())

        now = now.plusSeconds(8 * 24 * 60 * 60)

        assertNull(subject.read())
    }

    @Test
    fun `an unreadable cache degrades to nothing cached`() = runBlocking {
        File(temp.root, "home_feed.json").writeText("{ this is not json")

        assertNull(store().read())
    }

    @Test
    fun `an unknown field is ignored so an older cache still decodes`() = runBlocking {
        File(temp.root, "home_feed.json").writeText(
            """{"savedAtIso":"${now}","somethingRemoved":42,"feed":{},"sections":[]}""",
        )

        val cached = store().read()

        assertTrue(cached!!.feed.isEmpty)
        assertTrue(cached.sections.isEmpty())
    }
}
