package fm.rizx.player.data.remote.soundcloud

import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** Pure SoundCloud DTO → domain mappers. Identity is the permalink URL — SoundCloud has no short id. */
class SoundcloudMappersTest {

    private val scServiceId = ServiceList.SoundCloud.serviceId

    /** A NewPipe search row. Blank url/name are how NewPipe signals "no permalink / no title". */
    private fun item(
        url: String,
        name: String,
        durationSec: Long = 180,
        uploader: String? = "Indie Artist",
    ): StreamInfoItem =
        StreamInfoItem(scServiceId, url, name, StreamType.AUDIO_STREAM).apply {
            duration = durationSec
            uploaderName = uploader
        }

    @Test
    fun `toSoundcloudTrackOrNull uses the permalink url as identity`() {
        val url = "https://soundcloud.com/indie-artist/exclusive-remix"

        val track = item(url, "Exclusive Remix").toSoundcloudTrackOrNull()!!

        assertEquals("Exclusive Remix", track.title)
        assertEquals(SoundcloudIds.STREAMING, track.source.provider)
        assertEquals(url, track.source.id) // the URL *is* the id — there is no 11-char video id
        assertEquals(url, track.source.url)
        assertEquals("Indie Artist", track.artists.single().name)
        assertEquals(180_000L, track.durationMs) // seconds → ms
    }

    @Test
    fun `toSoundcloudTrackOrNull drops rows with a blank url or title`() {
        assertNull(item(url = "", name = "Exclusive Remix").toSoundcloudTrackOrNull())
        assertNull(item(url = "https://soundcloud.com/a/b", name = "").toSoundcloudTrackOrNull())
    }

    @Test
    fun `toSoundcloudTrackOrNull tolerates a missing uploader and zero duration`() {
        val track = item("https://soundcloud.com/a/b", "Untitled", durationSec = 0, uploader = null)
            .toSoundcloudTrackOrNull()!!

        assertEquals(emptyList<Any>(), track.artists)
        assertNull(track.durationMs) // duration ≤ 0 → unknown, not 0ms
    }

    @Test
    fun `toSoundcloudCandidateOrNull maps a search row to a candidate keyed by url`() {
        val url = "https://soundcloud.com/a/b"

        val candidate = item(url, "Indie").toSoundcloudCandidateOrNull()!!

        assertEquals(url, candidate.id)
        assertEquals(SoundcloudIds.STREAMING, candidate.source.provider)
        assertEquals(url, candidate.source.url)
    }

    @Test
    fun `Track_toSoundcloudCandidateOrNull short-circuits a soundcloud track and ignores others`() {
        val url = "https://soundcloud.com/a/b"
        val scTrack = Track(title = "Indie", source = ProviderRef(SoundcloudIds.STREAMING, url, url))

        val candidate = scTrack.toSoundcloudCandidateOrNull()!!
        assertEquals(url, candidate.id) // resolves this exact permalink, not a re-search by title
        assertEquals(SoundcloudIds.STREAMING, candidate.source.provider)
        assertEquals(url, candidate.source.url)

        // A track owned by a different provider is not a SoundCloud candidate.
        assertNull(Track(title = "X", source = ProviderRef("deezer", "123")).toSoundcloudCandidateOrNull())
    }
}
