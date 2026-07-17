package fm.rizx.player.data.local.media

import fm.rizx.player.data.repository.LocalLibraryRepositoryImpl
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure MediaStore-row mapping + the local content-URI resolution (no Android/Cursor needed). */
class LocalMediaTest {

    @Test
    fun `localTrack maps a MediaStore row to a local Track`() {
        val t = localTrack(
            id = 42, title = "Velvet Hours", artist = "Luci", artistId = 7,
            album = "Nightfall", albumId = 3, durationMs = 200_000, trackNumber = 5,
        )

        assertEquals("local", t.source.provider)
        assertEquals("42", t.source.id) // identity is the MediaStore _ID
        assertEquals("Velvet Hours", t.title)
        assertEquals("Luci", t.artists.single().name)
        assertEquals(ProviderRef("local", "artist:7"), t.artists.single().source)
        assertEquals("Nightfall", t.album!!.title)
        assertEquals(ProviderRef("local", "album:3"), t.album!!.source)
        assertEquals(200_000L, t.durationMs)
        assertEquals(5, t.trackNumber)
        assertEquals("content://media/external/audio/albumart/3", t.artwork.coverUrl())
    }

    @Test
    fun `localTrack tolerates unknown artist and album and zero duration`() {
        val t = localTrack(
            id = 1, title = "Untitled", artist = "<unknown>", artistId = null,
            album = "<unknown>", albumId = null, durationMs = 0, trackNumber = 0,
        )

        assertTrue(t.artists.isEmpty())
        assertNull(t.album)
        assertNull(t.durationMs) // 0 → unknown, not 0ms
        assertNull(t.trackNumber)
        assertNull(t.artwork)
        assertEquals("Untitled", t.title)
    }

    // localStream is pure (no context use), so a mocked Context is fine.
    private val repo = LocalLibraryRepositoryImpl(mockk())

    @Test
    fun `localStream builds a content uri for a local track`() {
        val s = repo.localStream(Track(title = "Velvet", source = ProviderRef("local", "42")))!!
        assertEquals("content://media/external/audio/media/42", s.url)
        assertEquals(StreamProtocol.FILE, s.protocol)
    }

    @Test
    fun `localStream returns null for a non-local track`() {
        assertNull(repo.localStream(Track(title = "Velvet", source = ProviderRef("deezer", "1"))))
    }

    @Test
    fun `localStream returns null for a namespaced album or artist ref`() {
        assertNull(repo.localStream(Track(title = "A", source = ProviderRef("local", "album:3"))))
        assertNull(repo.localStream(Track(title = "B", source = ProviderRef("local", "artist:7"))))
    }
}
