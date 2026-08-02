package fm.rizx.player.data.canvas

import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.toCanvasCandidateOrNull
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.CanvasAspect
import fm.rizx.player.domain.model.CanvasQuality
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/** The YouTube canvas source: picking a stream, and picking the right video to pick it from. */
class YoutubeCanvasProviderTest {

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
        override fun mix(videoId: String, limit: Int): List<StreamInfoItem> = error("not used")
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

    /**
     * A 360×360 stream — the shape of an auto-generated cover-art upload, measured on device.
     *
     * The frame size reaches [VideoStream] through its [ItagItem], not its resolution string, which is
     * also true of a real YouTube extraction; for any other service both stay 0 and the aspect falls back
     * to landscape (no veto), which is the safe direction.
     */
    private fun square(url: String): VideoStream =
        VideoStream.Builder()
            .setId("itag-square-$url")
            .setContent(url, true)
            .setMediaFormat(MediaFormat.MPEG_4)
            .setResolution("360p")
            .setIsVideoOnly(false)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setItagItem(
                ItagItem(18, ItagItem.ItagType.VIDEO, MediaFormat.MPEG_4, "360p").apply {
                    setWidth(360)
                    setHeight(360)
                },
            )
            .build()

    /** [muxed] is what YouTube still serves as `videoStreams` (usually a single 360p). */
    private fun infoWith(
        muxed: List<VideoStream>,
        videoOnly: List<VideoStream> = emptyList(),
        title: String = "GIRLS",
        uploader: String = "Luci",
        durationSec: Long = 200,
    ): StreamInfo =
        StreamInfo(ytServiceId, "https://youtu.be/vid", "https://youtu.be/vid", StreamType.VIDEO_STREAM, "vid", title, 0)
            .apply {
                videoStreams = muxed
                videoOnlyStreams = videoOnly
                uploaderName = uploader
                duration = durationSec
            }

    private fun item(url: String, name: String, durationSec: Long, uploader: String = "Luci"): StreamInfoItem =
        StreamInfoItem(ytServiceId, url, name, StreamType.VIDEO_STREAM).apply {
            duration = durationSec
            uploaderName = uploader
        }

    private fun deezerTrack(title: String = "GIRLS", artist: String = "Luci") = Track(
        title = title,
        artists = listOf(ArtistCredit(artist)),
        durationMs = 200_000,
        source = ProviderRef("deezer", "1"),
    )

    private fun youtubeTrack() = Track(
        title = "GIRLS",
        source = ProviderRef("youtube", "dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
    )

    private fun StreamInfo.canvas(maxHeight: Int = CanvasQuality.DATA_SAVER.maxHeight) =
        toCanvasCandidateOrNull("youtube", maxHeight, score = 100)

    private fun provider(client: YoutubeExtractorClient) = YoutubeCanvasProvider(client, Dispatchers.Unconfined)

    private suspend fun YoutubeCanvasProvider.canvasFor(track: Track, quality: CanvasQuality = CanvasQuality.DATA_SAVER) =
        resolve(track, CanvasAspect.LANDSCAPE, quality).firstOrNull()

    // ---- the mapper ----

    @Test
    fun `picks the largest stream that still fits the budget`() {
        val info = infoWith(muxed = listOf(video("hi", "1080p"), video("lo", "144p"), video("mid", "360p")))

        assertEquals("mid", info.canvas(maxHeight = 360)?.mediaUrl)
    }

    @Test
    fun `a tighter budget takes the smaller one`() {
        val info = infoWith(muxed = listOf(video("lo", "144p"), video("mid", "360p")))

        assertEquals("lo", info.canvas(maxHeight = 240)?.mediaUrl)
    }

    @Test
    fun `when nothing fits the budget, the smallest is better than none`() {
        val info = infoWith(muxed = listOf(video("big", "720p"), video("huge", "1080p")))

        assertEquals("big", info.canvas(maxHeight = 360)?.mediaUrl)
    }

    @Test
    fun `the runner-up becomes the fallback, instead of being thrown away`() {
        val info = infoWith(muxed = listOf(video("a", "360p"), video("b", "720p")))

        val candidate = info.canvas(maxHeight = 360)
        assertEquals("a", candidate?.mediaUrl)
        assertEquals("b", candidate?.fallbackUrl)
    }

    @Test
    fun `one stream means no fallback, not a duplicate`() {
        assertNull(infoWith(muxed = listOf(video("only", "360p"))).canvas()?.fallbackUrl)
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

        assertEquals("muxed360", info.canvas()?.mediaUrl)
    }

    @Test
    fun `ignores DASH, which would need a dependency we do not have`() {
        val info = infoWith(muxed = listOf(video("dash", "144p", progressive = false), video("prog", "720p")))

        assertEquals("prog", info.canvas()?.mediaUrl)
    }

    @Test
    fun `a video with no streams simply has no canvas`() {
        assertNull(infoWith(emptyList()).canvas())
    }

    @Test
    fun `a YouTube video is landscape unless its frame says otherwise`() {
        assertEquals(CanvasAspect.LANDSCAPE, infoWith(muxed = listOf(video("v", "360p"))).canvas()?.aspect)
    }

    // ---- the provider ----

    @Test
    fun `a youtube-sourced track uses its own video, with no search and no matching`() = runBlocking {
        // The user picked that upload when they imported it; there is nothing to second-guess.
        val client = FakeClient(info = { infoWith(muxed = listOf(video("v", "240p"))) })

        val candidate = provider(client).canvasFor(youtubeTrack())

        assertEquals("v", candidate?.mediaUrl)
        assertEquals(100, candidate?.score)
        assertEquals("it already knows its video", 0, client.searches)
    }

    @Test
    fun `a deezer track is matched by a plain video search, not YouTube Music songs`() = runBlocking {
        val client = FakeClient(
            items = listOf(item("https://youtu.be/abc123defgh", "GIRLS", 200)),
            info = { infoWith(muxed = listOf(video("v", "240p"))) },
        )

        assertEquals("v", provider(client).canvasFor(deezerTrack())?.mediaUrl)
        assertEquals("the music video, not the topic upload", 1, client.searches)
        assertEquals("Music 'songs' would return a still cover", 0, client.songSearches)
    }

    @Test
    fun `a search that only turns up other songs yields no canvas`() = runBlocking {
        // The bug this feature had: the first playable hit won unconditionally, so a one-word title put
        // somebody else's song on the screen.
        val client = FakeClient(
            items = listOf(
                item("https://youtu.be/aaaaaaaaaaa", "Girls Like You", 200, uploader = "MaroonVEVO"),
                item("https://youtu.be/bbbbbbbbbbb", "GIRLS (Sped Up)", 190, uploader = "Luci"),
            ),
            info = { infoWith(muxed = listOf(video("v", "240p"))) },
        )

        assertNull(provider(client).canvasFor(deezerTrack()))
        assertEquals("nothing matched, so nothing should have been extracted", 0, client.extractions)
    }

    @Test
    fun `the right video is found even when it is not the first hit`() = runBlocking {
        val client = FakeClient(
            items = listOf(
                item("https://youtu.be/aaaaaaaaaaa", "GIRLS — reaction", 900, uploader = "Some Channel"),
                item("https://youtu.be/bbbbbbbbbbb", "GIRLS", 228, uploader = "LuciVEVO"),
            ),
            info = { infoWith(muxed = listOf(video("v", "240p"))) },
        )

        // 28s longer than the track and credited to a VEVO channel — the official video, in other words.
        assertEquals("v", provider(client).canvasFor(deezerTrack())?.mediaUrl)
    }

    @Test
    fun `a track nothing matches has no canvas, and does not throw`() = runBlocking {
        assertNull(provider(FakeClient(items = emptyList())).canvasFor(deezerTrack()))
    }

    @Test
    fun `a broken extraction surfaces to the registry, which is what swallows it`() {
        // The provider no longer catches: the registry does, in one place, for every provider. Letting
        // it through is what allows the repository to tell "it broke" from "there isn't one" and cache
        // the two for very different lengths of time.
        val client = FakeClient(info = { throw ExtractionException("youtube changed again") })

        assertThrows(ExtractionException::class.java) {
            runBlocking { provider(client).canvasFor(youtubeTrack()) }
        }
    }

    // ---- the static filter, through the provider ----

    @Test
    fun `the track's own video does not get to skip the gates`() = runBlocking {
        // The first version accepted it unconditionally because "the user picked that upload" — but they
        // picked it to *listen* to, and this app's YouTube Music search returns topic uploads. That is
        // the still image the whole feature is trying to stop showing.
        val client = FakeClient(info = { infoWith(muxed = listOf(video("v", "240p"))) })
        val topic = youtubeTrack().copy(title = "GIRLS (Official Audio)")

        assertNull(provider(client).canvasFor(topic))
        assertEquals("and it must not have been extracted either", 0, client.extractions)
    }

    @Test
    fun `a topic upload at rank one loses to the real video at rank two`() = runBlocking {
        // The exact shape of the complaint: the search leads with the auto-generated art track.
        val client = FakeClient(
            items = listOf(
                item("https://youtu.be/aaaaaaaaaaa", "GIRLS", 200, uploader = "Luci - Topic"),
                item("https://youtu.be/bbbbbbbbbbb", "GIRLS", 205, uploader = "LuciVEVO"),
            ),
            info = { url -> infoWith(muxed = listOf(video(url, "360p"))) },
        )

        val chosen = provider(client).canvasFor(deezerTrack())

        assertTrue("expected the VEVO upload, got ${chosen?.mediaUrl}", chosen!!.mediaUrl.contains("bbbbbbbbbbb"))
    }

    @Test
    fun `the artist's own channel outranks a stranger with a better score`() = runBlocking {
        val client = FakeClient(
            items = listOf(
                item("https://youtu.be/aaaaaaaaaaa", "GIRLS", 200, uploader = "Random Uploads"),
                item("https://youtu.be/bbbbbbbbbbb", "GIRLS (Official Video)", 228, uploader = "LuciVEVO"),
            ),
            info = { url -> infoWith(muxed = listOf(video(url, "360p"))) },
        )

        assertTrue(provider(client).canvasFor(deezerTrack())!!.mediaUrl.contains("bbbbbbbbbbb"))
    }

    @Test
    fun `a lyric video is used only when nothing filmed the song`() = runBlocking {
        val onlyLyrics = FakeClient(
            items = listOf(item("https://youtu.be/aaaaaaaaaaa", "GIRLS (Video Letra)", 200, uploader = "Luci")),
            info = { url -> infoWith(muxed = listOf(video(url, "360p"))) },
        )
        assertTrue(provider(onlyLyrics).canvasFor(deezerTrack())!!.mediaUrl.contains("aaaaaaaaaaa"))

        val alsoFilmed = FakeClient(
            items = listOf(
                item("https://youtu.be/aaaaaaaaaaa", "GIRLS (Video Letra)", 200, uploader = "Luci"),
                item("https://youtu.be/bbbbbbbbbbb", "GIRLS", 200, uploader = "Luci"),
            ),
            info = { url -> infoWith(muxed = listOf(video(url, "360p"))) },
        )
        assertTrue(
            "the film outranks the lyric card",
            provider(alsoFilmed).canvasFor(deezerTrack())!!.mediaUrl.contains("bbbbbbbbbbb"),
        )
    }

    @Test
    fun `two equally good but different videos are a coin flip, so neither is shown`() = runBlocking {
        // "No escoger aleatoriamente el primero": a search that offers a tie has not identified anything.
        val client = FakeClient(
            items = listOf(
                item("https://youtu.be/aaaaaaaaaaa", "GIRLS", 200, uploader = "Luci"),
                item("https://youtu.be/bbbbbbbbbbb", "GIRLS", 200, uploader = "Luci"),
            ),
            info = { url -> infoWith(muxed = listOf(video(url, "360p"))) },
        )

        assertNull(provider(client).canvasFor(deezerTrack()))
        assertEquals("a refusal must not cost an extraction", 0, client.extractions)
    }

    @Test
    fun `a tie between different tiers is not a coin flip`() = runBlocking {
        // The artist's own channel is a real reason to prefer one, however close the scores.
        val client = FakeClient(
            items = listOf(
                item("https://youtu.be/aaaaaaaaaaa", "GIRLS", 200, uploader = "Random Uploads"),
                item("https://youtu.be/bbbbbbbbbbb", "GIRLS", 200, uploader = "LuciVEVO"),
            ),
            info = { url -> infoWith(muxed = listOf(video(url, "360p"))) },
        )

        assertTrue(provider(client).canvasFor(deezerTrack())!!.mediaUrl.contains("bbbbbbbbbbb"))
    }

    @Test
    fun `a square extracted frame is vetoed after the fact`() = runBlocking {
        // The last gate, and the only one that has seen the file. 360x360 is what a cover-art upload
        // measures — resolved correctly, played correctly, and completely still.
        val client = FakeClient(
            items = listOf(item("https://youtu.be/aaaaaaaaaaa", "GIRLS", 200, uploader = "Luci")),
            info = { infoWith(muxed = listOf(square("v"))) },
        )

        assertNull(provider(client).canvasFor(deezerTrack()))
    }

    @Test
    fun `a vetoed top hit falls through to the next one instead of giving up`() = runBlocking {
        // The real search result for "Ella Baila Sola", read off the device: the artist's own channel
        // carries both the square art track — perfect title, artist and duration, so it scores 100 and
        // is PREFERRED — and a moving lyric video. The veto only fires after an extraction, so stopping
        // at the winner meant no canvas at all for a song that plainly has one.
        val client = FakeClient(
            items = listOf(
                item("https://youtu.be/aaaaaaaaaaa", "Ella Baila Sola", 200, uploader = "Eslabon Armado"),
                item("https://youtu.be/bbbbbbbbbbb", "Ella Baila Sola (Video Con Letras)", 200, uploader = "Eslabon Armado"),
            ),
            info = { url ->
                if ("aaaaaaaaaaa" in url) infoWith(muxed = listOf(square("art")))
                else infoWith(muxed = listOf(video("film", "360p")))
            },
        )

        val track = deezerTrack("Ella Baila Sola", artist = "Eslabon Armado")
        assertEquals("film", provider(client).canvasFor(track)?.mediaUrl)
        assertEquals("both had to be extracted to find that out", 2, client.extractions)
    }

    @Test
    fun `falling through is bounded, so a stills-only search is not ten extractions`() = runBlocking {
        val client = FakeClient(
            items = (1..8).map { item("https://youtu.be/${"%011d".format(it)}", "GIRLS", 200, uploader = "Luci") },
            info = { infoWith(muxed = listOf(square("art"))) },
        )

        // The coin-flip rule bows out first here (eight identical hits), which is itself the right
        // answer; when it doesn't, MAX_EXTRACTIONS caps the cost.
        assertNull(provider(client).canvasFor(deezerTrack()))
        assertTrue("extractions should be bounded, was ${client.extractions}", client.extractions <= 3)
    }

    // ---- quality ----

    @Test
    fun `each quality tier takes the best rung that fits its cap`() = runBlocking {
        // In practice YouTube serves one muxed rung (itag 18, 360p), so this mostly changes nothing
        // there — the tiers earn their keep on Apple's HLS ladder. The mapper still has to honour them.
        val streams = listOf(video("sd", "360p"), video("hd", "720p"), video("fhd", "1080p"))
        val client = FakeClient(info = { infoWith(muxed = streams) })

        assertEquals("fhd", provider(client).canvasFor(youtubeTrack(), CanvasQuality.HIGH)?.mediaUrl)
        assertEquals("hd", provider(client).canvasFor(youtubeTrack(), CanvasQuality.AUTO)?.mediaUrl)
        assertEquals("sd", provider(client).canvasFor(youtubeTrack(), CanvasQuality.DATA_SAVER)?.mediaUrl)
    }

    // ---- the document's regression fixture (section 14) ----

    @Test
    fun `of the four GIRLS candidates, only the right one may win`() {
        // 1. someone else's "Girls"  2. a live take  3. the real thing  4. a Topic "Official Audio"
        val client = FakeClient(
            items = listOf(
                item("https://youtu.be/aaaaaaaaaaa", "Girls", 201, uploader = "Rita Ora"),
                item("https://youtu.be/bbbbbbbbbbb", "GIRLS (Live)", 214, uploader = "Luci"),
                item("https://youtu.be/ccccccccccc", "GIRLS", 200, uploader = "Luci"),
                item("https://youtu.be/ddddddddddd", "GIRLS (Official Audio)", 200, uploader = "Luci - Topic"),
            ),
            info = { url -> infoWith(muxed = listOf(video(url, "360p"))) },
        )

        val chosen = runBlocking { provider(client).canvasFor(deezerTrack()) }

        assertTrue("expected candidate 3, got ${chosen?.mediaUrl}", chosen!!.mediaUrl.contains("ccccccccccc"))
    }
}
