package fm.rizx.player.data.canvas

import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.toCanvasVideoUrlOrNull
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/** The Now Playing canvas: picking a video, and refusing to make a fuss when there isn't one. */
class CanvasSourceTest {

    private val ytServiceId = ServiceList.YouTube.serviceId

    private class FakeClient(
        val items: List<StreamInfoItem> = emptyList(),
        val info: (String) -> StreamInfo = { error("streamInfo not stubbed") },
    ) : YoutubeExtractorClient {
        var searches = 0
        var extractions = 0
        var songSearches = 0
        override fun searchSongs(query: String, limit: Int): List<StreamInfoItem> {
            songSearches++
            return items.take(limit)
        }
        override fun searchVideos(query: String, limit: Int): List<StreamInfoItem> {
            searches++
            return items.take(limit)
        }
        override fun streamInfo(videoUrl: String): StreamInfo {
            extractions++
            return info(videoUrl)
        }
        override fun searchPlaylists(query: String, limit: Int): List<org.schabi.newpipe.extractor.playlist.PlaylistInfoItem> = error("not used")
        override fun playlist(playlistUrl: String) = error("not used")
    }

    private fun video(url: String, resolution: String, progressive: Boolean = true, videoOnly: Boolean = false): VideoStream =
        VideoStream.Builder()
            .setId("itag-$resolution-$url")
            .setContent(url, true)
            .setMediaFormat(MediaFormat.MPEG_4)
            .setResolution(resolution)
            .setIsVideoOnly(videoOnly)
            .setDeliveryMethod(if (progressive) DeliveryMethod.PROGRESSIVE_HTTP else DeliveryMethod.DASH)
            .build()

    /** [muxed] is what YouTube still serves as `videoStreams` (usually a single 360p). */
    private fun infoWith(muxed: List<VideoStream>, videoOnly: List<VideoStream> = emptyList()): StreamInfo =
        StreamInfo(ytServiceId, "https://youtu.be/vid", "https://youtu.be/vid", StreamType.VIDEO_STREAM, "vid", "GIRLS", 0)
            .apply {
                videoStreams = muxed
                videoOnlyStreams = videoOnly
            }

    private fun item(url: String, name: String, durationSec: Long): StreamInfoItem =
        StreamInfoItem(ytServiceId, url, name, StreamType.VIDEO_STREAM).apply { duration = durationSec }

    private fun deezerTrack() =
        Track(title = "GIRLS", artists = listOf(ArtistCredit("Luci")), source = ProviderRef("deezer", "1"))

    private fun youtubeTrack() = Track(
        title = "GIRLS",
        source = ProviderRef("youtube", "dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
    )

    // ---- the mapper ----

    @Test
    fun `picks the lowest resolution, because it is a background not a cinema`() {
        val info = infoWith(muxed = listOf(video("hi", "1080p"), video("lo", "144p"), video("mid", "360p")))

        assertEquals("lo", info.toCanvasVideoUrlOrNull())
    }

    @Test
    fun `ignores the video-only ladder, which googlevideo throttles into a timeout`() {
        // What the live extractor really returns: one muxed 360p plus a video-only ladder down to 144p.
        // The 144p looks like the better pick and is even labelled PROGRESSIVE_HTTP — but without
        // range requests googlevideo starves it and ExoPlayer times out. Verified on device.
        val info = infoWith(
            muxed = listOf(video("muxed360", "360p")),
            videoOnly = listOf(video("only144", "144p", videoOnly = true)),
        )

        assertEquals("muxed360", info.toCanvasVideoUrlOrNull())
    }

    @Test
    fun `ignores DASH, which would need a dependency we do not have`() {
        val info = infoWith(muxed = listOf(video("dash", "144p", progressive = false), video("prog", "720p")))

        assertEquals("prog", info.toCanvasVideoUrlOrNull())
    }

    @Test
    fun `a video with no streams simply has no canvas`() {
        assertNull(infoWith(emptyList()).toCanvasVideoUrlOrNull())
    }

    // ---- the source ----

    @Test
    fun `a youtube-sourced track uses its own video, with no search`() = runBlocking {
        val client = FakeClient(info = { infoWith(muxed = listOf(video("v", "240p"))) })

        val url = YoutubeCanvasSource(client, Dispatchers.Unconfined).videoUrlFor(youtubeTrack())

        assertEquals("v", url)
        assertEquals("it already knows its video", 0, client.searches)
    }

    @Test
    fun `a deezer track is matched by a plain video search, not YouTube Music songs`() = runBlocking {
        val client = FakeClient(
            items = listOf(item("https://youtu.be/abc123defgh", "GIRLS", 200)),
            info = { infoWith(muxed = listOf(video("v", "240p"))) },
        )

        assertEquals("v", YoutubeCanvasSource(client, Dispatchers.Unconfined).videoUrlFor(deezerTrack()))
        assertEquals("the music video, not the topic upload", 1, client.searches)
        assertEquals("Music 'songs' would return a still cover", 0, client.songSearches)
    }

    @Test
    fun `a track nothing matches has no canvas, and does not throw`() = runBlocking {
        val client = FakeClient(items = emptyList())

        assertNull(YoutubeCanvasSource(client, Dispatchers.Unconfined).videoUrlFor(deezerTrack()))
    }

    @Test
    fun `a broken extraction is swallowed — decoration must never disturb playback`() = runBlocking {
        val client = FakeClient(info = { throw ExtractionException("youtube changed again") })

        assertNull(YoutubeCanvasSource(client, Dispatchers.Unconfined).videoUrlFor(youtubeTrack()))
    }

    @Test
    fun `the answer is cached, so re-opening the song costs nothing`() = runBlocking {
        val client = FakeClient(info = { infoWith(muxed = listOf(video("v", "240p"))) })
        val source = YoutubeCanvasSource(client, Dispatchers.Unconfined)

        source.videoUrlFor(youtubeTrack())
        source.videoUrlFor(youtubeTrack())

        assertEquals(1, client.extractions)
    }

    @Test
    fun `a miss is cached too, so a videoless song is not re-extracted forever`() = runBlocking {
        val client = FakeClient(info = { infoWith(emptyList()) })
        val source = YoutubeCanvasSource(client, Dispatchers.Unconfined)

        assertNull(source.videoUrlFor(youtubeTrack()))
        assertNull(source.videoUrlFor(youtubeTrack()))

        assertEquals(1, client.extractions)
    }
}
