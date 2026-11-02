package fm.rizx.player.data.discovery

import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.deezer.DeezerPlaylistsWrapper
import fm.rizx.player.domain.discovery.DiscoveryAnchor
import fm.rizx.player.domain.model.SoundGenre
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The creator rule, on a response trimmed from the real `GET /artist/276237911/playlists` (Bad Bunny,
 * 2026-11-02): Deezer's editors and charts mixed with a label's SEO lists and spam.
 */
class DeezerDiscoverySourceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val badBunnyPlaylists = json.decodeFromString<DeezerPlaylistsWrapper>(
        """
        {"data": [
          {"id": 1111, "title": "Top Mexico", "nb_tracks": 50, "user": {"id": 1, "name": "Deezer Charts"}},
          {"id": 2222, "title": "Fuego Latino", "nb_tracks": 60, "user": {"id": 2, "name": "Camojada - Deezer Latin Editor"}},
          {"id": 3333, "title": "100% Bad Bunny", "nb_tracks": 80, "user": {"id": 3, "name": "Deezer Artist Editor"}},
          {"id": 4444, "title": "REGETON 2026 REAGGETON, REGAETON, RAGGAETON", "user": {"id": 4, "name": "Filtr France"}},
          {"id": 5555, "title": "100% Rauw Alejandro", "user": {"id": 3, "name": "Deezer Artist Editor"}},
          {"id": 6666, "title": "TOP 50 MEXICO 2026", "user": {"id": 5, "name": "Top Playlists"}},
          {"id": 7777, "title": "No creator at all"}
        ]}
        """.trimIndent(),
    )

    @Test
    fun `keeps only Deezer's own lists, the artist's own first and charts last`() = runTest {
        val api = mockk<DeezerApi> { coEvery { artistPlaylists("276237911", any()) } returns badBunnyPlaylists }
        val source = DeezerDiscoverySource(api, io = UnconfinedTestDispatcher(testScheduler))

        val titles = source.forArtist(DiscoveryAnchor("Bad Bunny", "276237911")).map { it.name }

        assertEquals(listOf("100% Bad Bunny", "Fuego Latino", "100% Rauw Alejandro", "Top Mexico"), titles)
    }

    @Test
    fun `no Deezer id means no request`() = runTest {
        val api = mockk<DeezerApi>()
        val source = DeezerDiscoverySource(api, io = UnconfinedTestDispatcher(testScheduler))

        assertTrue(source.forArtist(DiscoveryAnchor("Someone", null)).isEmpty())
        assertTrue(source.forArtist(DiscoveryAnchor("Someone", "not-a-number")).isEmpty())
        coVerify(exactly = 0) { api.artistPlaylists(any(), any()) }
    }

    @Test
    fun `genre shelves ask the genre's own chart and are memoized`() = runTest {
        val api = mockk<DeezerApi> {
            coEvery { genrePlaylists("464", any()) } returns json.decodeFromString(
                """{"data": [{"id": 1, "title": "Metal Radar"}, {"id": 2, "title": "80s Metal"}]}""",
            )
        }
        val source = DeezerDiscoverySource(api, io = UnconfinedTestDispatcher(testScheduler))

        assertEquals(listOf("Metal Radar", "80s Metal"), source.forGenre(SoundGenre.METAL).map { it.name })
        source.forGenre(SoundGenre.METAL)
        coVerify(exactly = 1) { api.genrePlaylists("464", any()) }
        assertTrue("no Deezer genre for lo-fi", source.forGenre(SoundGenre.LOFI_CHILL).isEmpty())
    }

    @Test
    fun `a failing request is an empty shelf, not an exception`() = runTest {
        val api = mockk<DeezerApi> { coEvery { artistPlaylists(any(), any()) } throws IllegalStateException("down") }
        val source = DeezerDiscoverySource(api, io = UnconfinedTestDispatcher(testScheduler))

        assertTrue(source.forArtist(DiscoveryAnchor("Bad Bunny", "1")).isEmpty())
    }
}
