package fm.rizx.player.data.discovery

import fm.rizx.player.data.remote.applemusic.AppleMusicShelfPage
import fm.rizx.player.data.remote.itunes.ItunesApi
import fm.rizx.player.data.remote.itunes.ItunesResultDto
import fm.rizx.player.data.remote.itunes.ItunesSearchResponse
import fm.rizx.player.domain.discovery.DiscoveryAnchor
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SoundGenre
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicDiscoverySourceTest {

    private fun pl(id: String) = PlaylistRef(id = id, name = id, source = ProviderRef("applemusic", "playlist:$id"))

    private val itunes = mockk<ItunesApi> {
        coEvery { search(term = "Bad Bunny", entity = "musicArtist", limit = any(), country = any()) } returns ItunesSearchResponse(
            results = listOf(
                // A same-named stranger ranked first would be picked by "first row wins".
                ItunesResultDto(artistName = "Bad Bunny Tribute Band", artistId = 1),
                ItunesResultDto(artistName = "Bad Bunny", artistId = 1126808565),
            ),
        )
    }

    private fun source(pages: AppleMusicShelfPage, saving: Boolean = false, front: String = "us", scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        AppleMusicDiscoverySource(itunes, pages, storefront = { front }, savingData = { saving }, io = UnconfinedTestDispatcher(scheduler))

    @Test
    fun `the artist id is the exact name match, not the first row`() = runTest {
        val pages = mockk<AppleMusicShelfPage> { every { artistPlaylists("us", "1126808565") } returns listOf(pl("pl.essentials")) }

        val refs = source(pages, scheduler = testScheduler).forArtist(DiscoveryAnchor("Bad Bunny"))

        assertEquals(listOf("pl.essentials"), refs.map { it.id })
    }

    @Test
    fun `data saver skips Apple entirely, no request at all`() = runTest {
        val pages = mockk<AppleMusicShelfPage>()

        val s = source(pages, saving = true, scheduler = testScheduler)

        assertTrue(s.forArtist(DiscoveryAnchor("Bad Bunny")).isEmpty())
        assertTrue(s.forGenre(SoundGenre.POP).isEmpty())
        coVerify(exactly = 0) { itunes.search(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a curator missing from the storefront falls back to us`() = runTest {
        val pages = mockk<AppleMusicShelfPage> {
            every { curatorPlaylists("mx", "976439553") } returns emptyList()
            every { curatorPlaylists("us", "976439553") } returns listOf(pl("pl.urbano"))
        }

        val refs = source(pages, front = "mx", scheduler = testScheduler).forGenre(SoundGenre.REGGAETON)

        assertEquals(listOf("pl.urbano"), refs.map { it.id })
        verify { pages.curatorPlaylists("us", "976439553") }
    }

    @Test
    fun `a genre without an Apple curator asks nothing`() = runTest {
        val pages = mockk<AppleMusicShelfPage>()

        assertTrue(source(pages, scheduler = testScheduler).forGenre(SoundGenre.LOFI_CHILL).isEmpty())
    }
}
