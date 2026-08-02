package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.core.network.NetworkMonitor
import org.schabi.newpipe.extractor.stream.AudioTrackType
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.toBestAudioStreamOrNull
import fm.rizx.player.data.remote.youtube.youtubeVideoId
import fm.rizx.player.FakeSettingsRepository
import fm.rizx.player.dataSaverState
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.AudioQualityMode
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException

/** YouTube streaming provider + mappers, driven by a fake extractor client (no real network). */
class YoutubeStreamingProviderTest {

    private val ytServiceId = ServiceList.YouTube.serviceId

    private class FakeClient(
        val items: List<StreamInfoItem> = emptyList(),
        val info: (String) -> StreamInfo = { error("streamInfo not stubbed") },
        val searchThrows: Throwable? = null,
    ) : YoutubeExtractorClient {
        override fun searchSongs(query: String, limit: Int): List<StreamInfoItem> {
            searchThrows?.let { throw it }
            return items.take(limit)
        }
        override fun searchVideos(query: String, limit: Int): List<StreamInfoItem> = error("canvas search not used by the streaming provider")
        override fun searchPlaylists(query: String, limit: Int): List<org.schabi.newpipe.extractor.playlist.PlaylistInfoItem> = error("playlist search not used by the streaming provider")
        override fun streamInfo(videoUrl: String): StreamInfo = info(videoUrl)
        override fun playlist(playlistUrl: String) = error("playlist not used by the streaming provider")
        override fun mix(videoId: String, limit: Int): List<StreamInfoItem> = error("mix not used by the streaming provider")
    }

    private fun item(url: String, name: String, durationSec: Long): StreamInfoItem =
        StreamInfoItem(ytServiceId, url, name, StreamType.VIDEO_STREAM).apply { duration = durationSec }

    private fun audio(url: String, format: MediaFormat, bitrate: Int): AudioStream =
        AudioStream.Builder()
            .setId("itag-$bitrate")
            .setContent(url, true)
            .setMediaFormat(format)
            .setAverageBitrate(bitrate)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .build()

    private fun audioTrack(url: String, format: MediaFormat, bitrate: Int, type: AudioTrackType): AudioStream =
        AudioStream.Builder()
            .setId("itag-$bitrate-$type")
            .setContent(url, true)
            .setMediaFormat(format)
            .setAverageBitrate(bitrate)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setAudioTrackType(type)
            .build()

    private fun streamInfoWith(streams: List<AudioStream>): StreamInfo =
        StreamInfo(ytServiceId, "https://youtu.be/vid", "https://youtu.be/vid", StreamType.VIDEO_STREAM, "vid", "GIRLS", 0)
            .apply { audioStreams = streams }

    private fun track() =
        Track(title = "GIRLS", artists = listOf(ArtistCredit("Luci")), source = ProviderRef("deezer", "1"))

    private fun provider(
        client: YoutubeExtractorClient,
        dataSaverOn: Boolean = false,
        hiResOn: Boolean = false,
        net: NetworkMonitor.Snapshot = NetworkMonitor.Snapshot(isCellular = false, downstreamKbps = 0),
    ): YoutubeStreamingProvider {
        val monitor = mockk<NetworkMonitor> { every { snapshot() } returns net }
        val settings = FakeSettingsRepository().apply {
            dataSaverFlow.value = dataSaverOn
            audioQualityModeFlow.value =
                if (hiResOn) AudioQualityMode.BEST_AVAILABLE else AudioQualityMode.STANDARD
        }
        // A real DataSaverState over the same fake settings: the provider now asks it, not the store,
        // and folding the two switches is part of what these cases are checking.
        val saver = dataSaverState(settings, unmetered = net.isUnmetered)
        return YoutubeStreamingProvider(client, monitor, saver, io = Dispatchers.Unconfined)
    }

    /** The two streams YouTube really offers for a song: AAC 128 and the better Opus 160. */
    private fun aacAndOpus(): StreamInfo = streamInfoWith(
        listOf(
            audio("https://cdn/opus", MediaFormat.WEBMA_OPUS, 160),
            audio("https://cdn/m4a", MediaFormat.M4A, 128),
        ),
    )

    private fun ytCandidate() = StreamCandidate(
        id = "abcdefghij0", title = "GIRLS",
        source = ProviderRef("youtube", "abcdefghij0", "https://youtu.be/abcdefghij0"),
    )

    @Test
    fun `youtubeVideoId parses watch, short and embed urls`() {
        assertEquals("dQw4w9WgXcQ", youtubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=2"))
        assertEquals("dQw4w9WgXcQ", youtubeVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youtubeVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertNull(youtubeVideoId("https://example.com/not-a-video"))
    }

    @Test
    fun `searchForTrack maps candidates and drops live or unknown-duration rows`() = runBlocking {
        val client = FakeClient(
            items = listOf(
                item("https://www.youtube.com/watch?v=abcdefghij0", "GIRLS", 200),
                item("https://www.youtube.com/watch?v=abcdefghij1", "GIRLS (live)", 0), // dropped
            ),
        )
        val candidates = provider(client).searchForTrack(track())
        assertEquals(1, candidates.size)
        assertEquals("abcdefghij0", candidates.first().id)
        assertEquals(200_000L, candidates.first().durationMs)
    }

    @Test
    fun `searchForTrack returns empty for a blank term`() = runBlocking {
        val blank = Track(title = "", artists = emptyList(), source = ProviderRef("deezer", "2"))
        assertTrue(provider(FakeClient()).searchForTrack(blank).isEmpty())
    }

    @Test
    fun `getStreamUrl prefers M4A over a higher-bitrate opus by default`() = runBlocking {
        val stream = provider(FakeClient(info = { aacAndOpus() })).getStreamUrl(ytCandidate())
        assertEquals("https://cdn/m4a", stream.url)
    }

    @Test
    fun `getStreamUrl with Hi-Res on takes the opus stream — better codec, and 48 kHz so nothing resamples`() =
        runBlocking {
            val stream = provider(FakeClient(info = { aacAndOpus() }), hiResOn = true).getStreamUrl(ytCandidate())
            assertEquals("https://cdn/opus", stream.url)
        }

    @Test
    fun `downloads stay on M4A even with Hi-Res on, because only that container can be tagged`() =
        runBlocking {
            val provider = provider(FakeClient(info = { aacAndOpus() }), hiResOn = true)
            assertEquals("https://cdn/opus", provider.getStreamUrl(ytCandidate()).url)
            assertEquals("https://cdn/m4a", provider.getDownloadStreamUrl(ytCandidate()).url)
        }

    @Test
    fun `a weak signal alone no longer downgrades — only the user's data saver does`() = runBlocking {
        val weak = NetworkMonitor.Snapshot(isCellular = false, downstreamKbps = 900)
        val info = streamInfoWith(
            listOf(
                audio("https://cdn/m4a128", MediaFormat.M4A, 128),
                audio("https://cdn/m4a48", MediaFormat.M4A, 48),
            ),
        )
        val stream = provider(FakeClient(info = { info }), dataSaverOn = false, net = weak)
            .getStreamUrl(ytCandidate())
        assertEquals("https://cdn/m4a128", stream.url)
    }

    @Test
    fun `the original audio track wins over a dubbed one`() {
        val info = streamInfoWith(
            listOf(
                audioTrack("https://cdn/dubbed", MediaFormat.M4A, 160, AudioTrackType.DUBBED),
                audioTrack("https://cdn/original", MediaFormat.M4A, 128, AudioTrackType.ORIGINAL),
            ),
        )
        assertEquals("https://cdn/original", info.toBestAudioStreamOrNull(ytCandidate())!!.url)
    }

    @Test
    fun `toBestAudioStreamOrNull honours preferLow by picking the lowest bitrate`() {
        val info = streamInfoWith(
            listOf(
                audio("https://cdn/opus160", MediaFormat.WEBMA_OPUS, 160),
                audio("https://cdn/m4a128", MediaFormat.M4A, 128),
                audio("https://cdn/m4a48", MediaFormat.M4A, 48),
            ),
        )
        val candidate = StreamCandidate(id = "abcdefghij0", title = "GIRLS", source = ProviderRef("youtube", "abcdefghij0"))

        // Not the 48 kbps floor: data saver picks the lowest stream still worth listening to (≥35% of the
        // best on offer), so saving data doesn't mean a stream that sounds broken.
        assertEquals("https://cdn/m4a128", info.toBestAudioStreamOrNull(candidate, preferLow = true)!!.url)
        assertEquals("https://cdn/m4a128", info.toBestAudioStreamOrNull(candidate, preferLow = false)!!.url)
        assertEquals(
            "https://cdn/opus160",
            info.toBestAudioStreamOrNull(candidate, preferLow = false, maxQuality = true)!!.url,
        )
    }

    @Test
    fun `toBestAudioStreamOrNull tags an HLS delivery as HLS, a progressive one as HTTPS`() {
        // The same mapper serves SoundCloud, whose only stream can be HLS-only. Mislabelling an `.m3u8` as
        // HTTPS would break the player's HLS retry path and let it be "downloaded" as a 2 KB manifest.
        val candidate = StreamCandidate(id = "abcdefghij0", title = "GIRLS", source = ProviderRef("youtube", "abcdefghij0"))

        val progressive = streamInfoWith(listOf(audio("https://cdn/m4a", MediaFormat.M4A, 128)))
        assertEquals(StreamProtocol.HTTPS, progressive.toBestAudioStreamOrNull(candidate)!!.protocol)

        val hls = streamInfoWith(
            listOf(
                AudioStream.Builder()
                    .setId("hls-mp3")
                    .setContent("https://cdn/playlist.m3u8", true)
                    .setMediaFormat(MediaFormat.MP3)
                    .setAverageBitrate(128)
                    .setDeliveryMethod(DeliveryMethod.HLS)
                    .build(),
            ),
        )
        assertEquals(StreamProtocol.HLS, hls.toBestAudioStreamOrNull(candidate)!!.protocol)
    }

    @Test
    fun `getStreamUrl on data saver plus cellular drops to a lower bitrate`() = runBlocking {
        val info = streamInfoWith(
            listOf(
                audio("https://cdn/m4a128", MediaFormat.M4A, 128),
                audio("https://cdn/m4a48", MediaFormat.M4A, 48),
            ),
        )
        val candidate = StreamCandidate(
            id = "abcdefghij0", title = "GIRLS",
            source = ProviderRef("youtube", "abcdefghij0", "https://youtu.be/abcdefghij0"),
        )
        val stream = provider(
            FakeClient(info = { info }),
            dataSaverOn = true,
            net = NetworkMonitor.Snapshot(isCellular = true, downstreamKbps = 0),
        ).getStreamUrl(candidate)

        assertEquals("https://cdn/m4a48", stream.url)
    }

    @Test
    fun `getStreamUrl with no audio streams throws ProviderFailure`() {
        val candidate = StreamCandidate(id = "abcdefghij0", title = "GIRLS", source = ProviderRef("youtube", "abcdefghij0"))
        val error = assertThrows(AppError::class.java) {
            runBlocking { provider(FakeClient(info = { streamInfoWith(emptyList()) })).getStreamUrl(candidate) }
        }
        assertTrue(error is AppError.ProviderFailure)
    }

    @Test
    fun `an extraction failure surfaces as a typed ProviderFailure`() {
        val client = FakeClient(searchThrows = ExtractionException("YouTube changed its layout"))
        val error = assertThrows(AppError::class.java) { runBlocking { provider(client).searchForTrack(track()) } }
        assertTrue(error is AppError.ProviderFailure)
    }

    @Test
    fun `a connectivity failure surfaces as AppError_Network`() {
        val client = FakeClient(searchThrows = IOException("no route to host"))
        val error = assertThrows(AppError::class.java) { runBlocking { provider(client).searchForTrack(track()) } }
        assertTrue(error is AppError.Network)
    }
}
