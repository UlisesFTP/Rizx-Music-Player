package fm.rizx.player.data.search

import fm.rizx.player.data.remote.soundcloud.SoundcloudExtractorClient
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException

/** The Search "Underground" orchestrator: YouTube + SoundCloud in parallel, degrading independently. */
class StreamingSourcesSearchTest {

    private val ytServiceId = ServiceList.YouTube.serviceId
    private val scServiceId = ServiceList.SoundCloud.serviceId

    private fun ytItem(videoId: String, name: String): StreamInfoItem =
        StreamInfoItem(ytServiceId, "https://www.youtube.com/watch?v=$videoId", name, StreamType.VIDEO_STREAM)
            .apply { duration = 200 }

    private fun scItem(url: String, name: String): StreamInfoItem =
        StreamInfoItem(scServiceId, url, name, StreamType.AUDIO_STREAM).apply {
            duration = 200
            uploaderName = "Artist"
        }

    private class FakeYoutube(
        private val items: List<StreamInfoItem>,
        private val throws: Throwable? = null,
    ) : YoutubeExtractorClient {
        override fun searchSongs(query: String, limit: Int): List<StreamInfoItem> = error("not used")
        override fun searchVideos(query: String, limit: Int): List<StreamInfoItem> {
            throws?.let { throw it }
            return items.take(limit)
        }
        override fun searchPlaylists(query: String, limit: Int): List<org.schabi.newpipe.extractor.playlist.PlaylistInfoItem> = error("not used")
        override fun streamInfo(videoUrl: String): StreamInfo = error("not used")
        override fun playlist(playlistUrl: String) = error("not used")
    }

    private class FakeSoundcloud(
        private val items: List<StreamInfoItem>,
        private val throws: Throwable? = null,
    ) : SoundcloudExtractorClient {
        override fun searchTracks(query: String, limit: Int): List<StreamInfoItem> {
            throws?.let { throw it }
            return items.take(limit)
        }
        override fun streamInfo(trackUrl: String): StreamInfo = error("not used")
    }

    private fun search(youtube: FakeYoutube, soundcloud: FakeSoundcloud) =
        NewPipeStreamingSourcesSearch(youtube, soundcloud, io = Dispatchers.Unconfined)

    @Test
    fun `search merges YouTube then SoundCloud results`() = runBlocking {
        val results = search(
            FakeYoutube(listOf(ytItem("abcdefghij0", "Exclusive Remix"))),
            FakeSoundcloud(listOf(scItem("https://soundcloud.com/a/b", "Indie Demo"))),
        ).search("test")

        assertEquals(2, results.tracks.size)
        assertEquals("youtube", results.tracks[0].source.provider) // YouTube first — the screen groups by source
        assertEquals("Exclusive Remix", results.tracks[0].title)
        assertEquals("soundcloud", results.tracks[1].source.provider)
        assertEquals("Indie Demo", results.tracks[1].title)
    }

    @Test
    fun `a failing source degrades independently, the other still shows`() = runBlocking {
        val results = search(
            FakeYoutube(emptyList(), throws = IOException("youtube unreachable")),
            FakeSoundcloud(listOf(scItem("https://soundcloud.com/a/b", "Indie Demo"))),
        ).search("test")

        assertEquals(1, results.tracks.size)
        assertEquals("soundcloud", results.tracks.single().source.provider)
    }

    @Test
    fun `both sources failing yields an empty result, never a crash`() = runBlocking {
        val results = search(
            FakeYoutube(emptyList(), throws = IOException("down")),
            FakeSoundcloud(emptyList(), throws = IOException("down")),
        ).search("test")

        assertTrue(results.tracks.isEmpty())
    }

    @Test
    fun `a blank query returns nothing without touching the clients`() = runBlocking {
        val results = NewPipeStreamingSourcesSearch(
            FakeYoutube(emptyList(), throws = IllegalStateException("must not be called")),
            FakeSoundcloud(emptyList(), throws = IllegalStateException("must not be called")),
        ).search("   ")

        assertTrue(results.tracks.isEmpty())
    }
}
