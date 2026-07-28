package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.YoutubePlaylistData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** YouTube / YouTube Music playlist import, driven by a fake extractor client (no real network). */
class YoutubePlaylistProviderTest {

    private val ytServiceId = ServiceList.YouTube.serviceId

    private class FakeClient(private val data: YoutubePlaylistData) : YoutubeExtractorClient {
        override fun searchSongs(query: String, limit: Int): List<StreamInfoItem> = emptyList()
        override fun searchVideos(query: String, limit: Int): List<StreamInfoItem> = emptyList()
        override fun searchPlaylists(query: String, limit: Int): List<org.schabi.newpipe.extractor.playlist.PlaylistInfoItem> = emptyList()
        override fun streamInfo(videoUrl: String): StreamInfo = error("not used")
        override fun playlist(playlistUrl: String): YoutubePlaylistData = data
        override fun mix(videoId: String, limit: Int): List<StreamInfoItem> = emptyList()
    }

    private fun item(videoId: String, title: String, uploader: String, durationSec: Long): StreamInfoItem =
        StreamInfoItem(ytServiceId, "https://www.youtube.com/watch?v=$videoId", title, StreamType.VIDEO_STREAM)
            .apply {
                duration = durationSec
                uploaderName = uploader
            }

    private fun provider(data: YoutubePlaylistData) =
        YoutubePlaylistProvider(FakeClient(data), io = Dispatchers.Unconfined)

    @Test
    fun `canHandle accepts youtube and youtube music playlists, rejects mixes and private lists`() {
        val p = provider(YoutubePlaylistData(null, null, emptyList()))
        assertTrue(p.canHandle("https://www.youtube.com/playlist?list=PLFgquLnL59alW3xmYiWRaoz0oM3H17Lth"))
        // YouTube Music is the whole point of this provider — NewPipe accepts the host natively.
        assertTrue(p.canHandle("https://music.youtube.com/playlist?list=OLAK5uy_kZbSMBTHhFbNy9ZuNXjF6a8sZVvpFEXAM"))
        assertTrue(p.canHandle("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLFgquLnL59alW3xmYiWRaoz0oM3H17Lth"))
        assertFalse(p.canHandle("https://www.youtube.com/watch?v=dQw4w9WgXcQ")) // a video, not a playlist
        assertFalse(p.canHandle("https://open.spotify.com/playlist/abc"))
    }

    @Test
    fun `maps playlist items to tracks keyed by their exact video id`() = runBlocking {
        val data = YoutubePlaylistData(
            name = "My Mix",
            uploaderName = "Some Channel",
            items = listOf(
                item("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", 213),
                item("abcdefghij0", "Second Song", "Someone", 180),
                item("liveitem123", "Live now", "Someone", 0), // live/unknown duration → dropped
            ),
        )

        val preview = provider(data).fetchPlaylist("https://www.youtube.com/playlist?list=PLFgquLnL59alW3xmYiWRaoz0oM3H17Lth")

        assertEquals("My Mix", preview.name)
        assertEquals(listOf("Never Gonna Give You Up", "Second Song"), preview.tracks.map { it.title })
        assertEquals(213_000L, preview.tracks[0].durationMs) // NewPipe gives seconds → ms
        assertEquals("Rick Astley", preview.tracks[0].artists.single().name)
        // The exact video id becomes the track identity, so playback doesn't re-search by artist/title.
        assertEquals("youtube:dQw4w9WgXcQ", preview.tracks[0].source.identityKey)
    }

    @Test
    fun `says so when the playlist was too long to import in full`() = runBlocking {
        val data = YoutubePlaylistData(
            name = "Huge",
            uploaderName = null,
            items = listOf(item("dQw4w9WgXcQ", "A", "X", 100)),
            truncated = true,
        )

        val preview = provider(data).fetchPlaylist("https://www.youtube.com/playlist?list=PLFgquLnL59alW3xmYiWRaoz0oM3H17Lth")

        assertTrue(preview.description!!.contains("first 1 tracks"))
    }

    @Test
    fun `a playlist with nothing importable fails as a typed provider error`() {
        val data = YoutubePlaylistData("Empty", null, emptyList())

        val error = assertThrows(AppError::class.java) {
            runBlocking {
                provider(data).fetchPlaylist("https://www.youtube.com/playlist?list=PLFgquLnL59alW3xmYiWRaoz0oM3H17Lth")
            }
        }
        assertTrue(error is AppError.ProviderFailure)
    }
}
