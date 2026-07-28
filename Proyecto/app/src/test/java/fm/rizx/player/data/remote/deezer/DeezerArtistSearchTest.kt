package fm.rizx.player.data.remote.deezer

import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeezerArtistSearchTest {

    private fun artists(vararg named: Pair<Long, String>) =
        DeezerArtistsWrapper(named.map { (id, name) -> DeezerArtistShortDto(id = id, name = name) })

    @Test
    fun `a credit that already carries a Deezer id needs no search`() = runTest {
        val api = mockk<DeezerApi>()
        val credit = ArtistCredit(name = "Modjo", source = ProviderRef("deezer", "artist:27"))

        assertEquals("27", DeezerArtistSearch(api).idFor(credit))
        coVerify(exactly = 0) { api.searchArtists(any(), any()) }
    }

    @Test
    fun `a YouTube channel name resolves to the real artist id`() = runTest {
        // The whole point: Deezer answers nothing for "ModjoOfficial" and finds the band for "modjo",
        // so without this the artist radio had no id to work with and "next" ran dry.
        val api = mockk<DeezerApi> {
            coEvery { searchArtists("ModjoOfficial", any()) } returns artists()
            coEvery { searchArtists("modjo", any()) } returns artists(27L to "Modjo")
        }

        assertEquals("27", DeezerArtistSearch(api).idFor(ArtistCredit(name = "ModjoOfficial")))
    }

    @Test
    fun `an ordinary name is searched once, as given`() = runTest {
        val api = mockk<DeezerApi> {
            coEvery { searchArtists("Coldplay", any()) } returns artists(892L to "Coldplay")
        }

        assertEquals("892", DeezerArtistSearch(api).idFor("Coldplay"))
        coVerify(exactly = 1) { api.searchArtists(any(), any()) }
    }

    @Test
    fun `a result that isn't the same artist is rejected`() = runTest {
        // Seeding the radio from a tribute band would quietly play someone else's music.
        val api = mockk<DeezerApi> {
            coEvery { searchArtists(any(), any()) } returns artists(1L to "Coldplay Tribute Band")
        }

        assertNull(DeezerArtistSearch(api).idFor("Coldplay"))
    }

    @Test
    fun `an id further down the results is still found`() = runTest {
        val api = mockk<DeezerApi> {
            coEvery { searchArtists(any(), any()) } returns
                artists(1L to "Coldplay Karaoke", 2L to "Coldplay Tribute", 892L to "Coldplay")
        }

        assertEquals("892", DeezerArtistSearch(api).idFor("Coldplay"))
    }

    @Test
    fun `a broken or empty search resolves to null instead of throwing`() = runTest {
        val broken = mockk<DeezerApi> { coEvery { searchArtists(any(), any()) } throws IOException("down") }
        val empty = mockk<DeezerApi> { coEvery { searchArtists(any(), any()) } returns artists() }

        assertNull(DeezerArtistSearch(broken).idFor("Coldplay"))
        assertNull(DeezerArtistSearch(empty).idFor("Coldplay"))
        assertNull(DeezerArtistSearch(empty).idFor(name = null))
        assertNull(DeezerArtistSearch(empty).idFor("   "))
    }

    @Test
    fun `concurrent callers asking the same thing make one request`() = runTest {
        // Starting a song wakes three askers at once — the Search screen, the player's artist link and
        // the radio's id lookup — and they all wanted the identical `search/artist?q=…`.
        val gate = CompletableDeferred<Unit>()
        val api = mockk<DeezerApi> {
            coEvery { searchArtists(any(), any()) } coAnswers {
                gate.await()
                artists(892L to "Coldplay")
            }
        }
        val subject = DeezerArtistSearch(api)

        val calls = (1..4).map { async { subject.idFor("Coldplay") } }
        // Let all four enter and pile up behind the first — the test scheduler is sequential, so
        // completing the gate any earlier would let each finish before the next began.
        testScheduler.runCurrent()
        gate.complete(Unit)

        assertEquals(listOf("892", "892", "892", "892"), calls.awaitAll())
        coVerify(exactly = 1) { api.searchArtists(any(), any()) }
    }

    @Test
    fun `a repeat within the TTL is answered from the memo`() = runTest {
        // The real fix for the burst: the player's lookup lives in a `mapLatest` and gets cancelled and
        // restarted by queue changes, and a restarted call is a *new* one that single-flight can't merge.
        val api = mockk<DeezerApi> { coEvery { searchArtists(any(), any()) } returns artists(892L to "Coldplay") }
        val subject = DeezerArtistSearch(api)

        repeat(4) { subject.idFor("Coldplay") }

        coVerify(exactly = 1) { api.searchArtists(any(), any()) }
    }

    @Test
    fun `the memo expires`() = runTest {
        var now = 0L
        val api = mockk<DeezerApi> { coEvery { searchArtists(any(), any()) } returns artists(892L to "Coldplay") }
        val subject = DeezerArtistSearch(api, nowMs = { now }, ttlMs = 1_000L)

        subject.idFor("Coldplay")
        now = 1_001L
        subject.idFor("Coldplay")

        coVerify(exactly = 2) { api.searchArtists(any(), any()) }
    }

    @Test
    fun `a miss is remembered briefly, then retried`() = runTest {
        // It has to be remembered at all, or the burst survives for exactly the artists Deezer lacks —
        // which is when it fires hardest. But only briefly, so a transient failure isn't sticky.
        var now = 0L
        val api = mockk<DeezerApi> { coEvery { searchArtists(any(), any()) } returns artists() }
        val subject = DeezerArtistSearch(api, nowMs = { now }, ttlMs = 600_000L, missTtlMs = 1_000L)

        repeat(4) { subject.idFor("Some Bedroom Producer") }
        coVerify(exactly = 1) { api.searchArtists(any(), any()) }

        now = 1_001L
        subject.idFor("Some Bedroom Producer")
        coVerify(exactly = 2) { api.searchArtists(any(), any()) }
    }
}
