package fm.rizx.player.data.local.store

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class PlaylistShareStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "playlist_shares.json")

    private fun share(
        id: String = "share-1",
        url: String = "https://example.test/functions/v1/playlist-shares/token",
        expires: String = "2026-08-27T16:49:59Z",
    ) = StoredPlaylistShare(id = id, url = url, expiresAtIso = expires, createdAtIso = "2026-08-20T16:49:59Z")

    @Test
    fun `a link survives the round trip, keyed by playlist`() = runTest {
        val f = file()
        PlaylistShareStore(f).put("playlist-a", share())

        val read = PlaylistShareStore(f).get("playlist-a")!!

        assertEquals("share-1", read.id)
        assertEquals("https://example.test/functions/v1/playlist-shares/token", read.url)
        assertEquals("2026-08-27T16:49:59Z", read.expiresAtIso)
        assertEquals("2026-08-20T16:49:59Z", read.createdAtIso)
    }

    @Test
    fun `playlists keep their own links`() = runTest {
        val f = file()
        val store = PlaylistShareStore(f)
        store.put("playlist-a", share(id = "a"))
        store.put("playlist-b", share(id = "b"))

        assertEquals("a", PlaylistShareStore(f).get("playlist-a")?.id)
        assertEquals("b", PlaylistShareStore(f).get("playlist-b")?.id)
        assertNull(PlaylistShareStore(f).get("playlist-c"))
    }

    @Test
    fun `creating a second link for the same playlist replaces the first`() = runTest {
        val f = file()
        val store = PlaylistShareStore(f)
        store.put("playlist-a", share(id = "old"))
        store.put("playlist-a", share(id = "new"))

        assertEquals("new", PlaylistShareStore(f).get("playlist-a")?.id)
    }

    @Test
    fun `revocation forgets by share id, because that is all revoke knows`() = runTest {
        val f = file()
        val store = PlaylistShareStore(f)
        store.put("playlist-a", share(id = "a"))
        store.put("playlist-b", share(id = "b"))

        store.removeByShareId("a")

        assertNull(PlaylistShareStore(f).get("playlist-a"))
        assertEquals("b", PlaylistShareStore(f).get("playlist-b")?.id)
    }

    @Test
    fun `removing the last entry deletes the file rather than leaving an empty one`() = runTest {
        val f = file()
        val store = PlaylistShareStore(f)
        store.put("playlist-a", share())

        store.remove("playlist-a")

        assertFalse(f.exists())
        assertNull(PlaylistShareStore(f).get("playlist-a"))
    }

    @Test
    fun `a corrupt file degrades to nothing remembered instead of throwing`() = runTest {
        val f = file()
        f.writeText("{ this is not json")

        assertNull(PlaylistShareStore(f).get("playlist-a"))
    }

    @Test
    fun `expiry is read from the record, and an undatable one counts as expired`() {
        val now = Instant.parse("2026-08-21T00:00:00Z")

        assertTrue(share(expires = "2026-08-27T16:49:59Z").isLiveAt(now))
        assertFalse(share(expires = "2026-08-20T16:49:59Z").isLiveAt(now))
        assertFalse("a link we can't date is one we shouldn't promise", share(expires = "").isLiveAt(now))
    }
}
