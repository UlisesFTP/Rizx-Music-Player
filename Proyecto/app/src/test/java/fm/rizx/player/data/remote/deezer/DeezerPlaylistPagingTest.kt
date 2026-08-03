package fm.rizx.player.data.remote.deezer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `/playlist/{id}` stops at 400 tracks **and omits the `next` link**, so nothing in the response says it
 * was cut — a 1640-track playlist imported as 400 and looked complete. These pin the paging that fixes it.
 */
class DeezerPlaylistPagingTest {

    /** Records the pages requested so the tests can assert no wasted calls. */
    private class FakeApi(private val total: Int) : DeezerApi by NotImplemented {
        val requested = mutableListOf<Pair<Int, Int>>()
        var emptyFrom: Int? = null

        override suspend fun playlistTracks(id: String, index: Int, limit: Int): DeezerPagedTracksDto {
            requested += index to limit
            if (emptyFrom != null && index >= emptyFrom!!) return DeezerPagedTracksDto(emptyList(), total)
            val end = minOf(index + limit, total)
            val rows = (index until end).map { DeezerTrackDto(id = it.toLong(), title = "t$it") }
            return DeezerPagedTracksDto(rows, total)
        }
    }

    private fun rows(count: Int, from: Int = 0) =
        (from until from + count).map { DeezerTrackDto(id = it.toLong(), title = "t$it") }

    @Test
    fun `a short playlist costs no extra request`() = runBlocking {
        val api = FakeApi(total = 120)

        val all = api.allPlaylistTracks("1", embedded = rows(120), declaredTotal = 120)

        assertEquals(120, all.size)
        assertEquals(emptyList<Pair<Int, Int>>(), api.requested)
    }

    @Test
    fun `pages past the 400 the playlist endpoint embeds`() = runBlocking {
        val api = FakeApi(total = 1_640)

        val all = api.allPlaylistTracks("1", embedded = rows(400), declaredTotal = 1_640)

        assertEquals(1_640, all.size)
        // Continues from where the embedded page stopped, never re-fetching it.
        assertEquals(400, api.requested.first().first)
        assertEquals(listOf("t0", "t400", "t1639"), listOf(all[0].title, all[400].title, all[1639].title))
    }

    @Test
    fun `an unknown total is left alone rather than paged blindly`() = runBlocking {
        val api = FakeApi(total = 900)

        val all = api.allPlaylistTracks("1", embedded = rows(400), declaredTotal = null)

        assertEquals(400, all.size)
        assertEquals(emptyList<Pair<Int, Int>>(), api.requested)
    }

    @Test
    fun `an empty page stops the walk instead of spinning on the same index`() = runBlocking {
        // A playlist that shrank mid-import: the total still claims more than Deezer will hand back.
        val api = FakeApi(total = 2_000).apply { emptyFrom = 900 }

        val all = api.allPlaylistTracks("1", embedded = rows(400), declaredTotal = 2_000)

        assertEquals(900, all.size)
        assertEquals(listOf(400, 900), api.requested.map { it.first })
    }

    @Test
    fun `a pathological playlist cannot page forever`() = runBlocking {
        val api = FakeApi(total = 500_000)

        val all = api.allPlaylistTracks("1", embedded = rows(400), declaredTotal = 500_000)

        assertEquals(5_000, all.size)
    }

    /** Only [DeezerApi.playlistTracks] is exercised here; everything else must never be called. */
    private companion object NotImplemented : DeezerApi {
        override suspend fun searchTracks(query: String, limit: Int) = throw UnsupportedOperationException()
        override suspend fun searchArtists(query: String, limit: Int) = throw UnsupportedOperationException()
        override suspend fun searchAlbums(query: String, limit: Int) = throw UnsupportedOperationException()
        override suspend fun searchPlaylists(query: String, limit: Int) = throw UnsupportedOperationException()
        override suspend fun album(id: String) = throw UnsupportedOperationException()
        override suspend fun artist(id: String) = throw UnsupportedOperationException()
        override suspend fun artistTop(id: String, limit: Int) = throw UnsupportedOperationException()
        override suspend fun artistRadio(id: String, limit: Int) = throw UnsupportedOperationException()
        override suspend fun artistAlbums(id: String, limit: Int, index: Int) = throw UnsupportedOperationException()
        override suspend fun artistRelated(id: String, limit: Int) = throw UnsupportedOperationException()
        override suspend fun chart() = throw UnsupportedOperationException()
        override suspend fun playlist(id: String) = throw UnsupportedOperationException()
        override suspend fun playlistTracks(id: String, index: Int, limit: Int) = throw UnsupportedOperationException()
        override suspend fun radioLists(limit: Int) = throw UnsupportedOperationException()
        override suspend fun radioTracks(id: String, limit: Int) = throw UnsupportedOperationException()
    }
}
