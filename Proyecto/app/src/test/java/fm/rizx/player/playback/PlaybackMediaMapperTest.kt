package fm.rizx.player.playback

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.QueueItem
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaybackMediaMapperTest {

    private fun track(durationMs: Long? = 204_000L, artists: List<ArtistCredit> = listOf(ArtistCredit(name = "Maya Sol"))) =
        Track(
            title = "Velvet Hours",
            artists = artists,
            album = AlbumRef(title = "Amber Tide", source = ProviderRef("meta", "al-amber")),
            durationMs = durationMs,
            source = ProviderRef("meta", "tr-velvet"),
        )

    private fun queueItem(track: Track, id: String = "queue-uuid-1") =
        QueueItem(id = id, track = track, addedAtIso = "2026-01-01T00:00:00Z")

    private fun stream(durationMs: Long? = 5_000L) =
        Stream(url = "asset:///fake_stream.wav", protocol = StreamProtocol.FILE, durationMs = durationMs, source = ProviderRef("streaming", "c1"))

    @Test
    fun `media spec uses the queue item id, not the track provider id`() {
        val track = track()
        val item = queueItem(track)

        val spec = toMediaSpec(item, stream())

        assertEquals("queue-uuid-1", spec.mediaId)
        assertNotEquals(track.source.id, spec.mediaId)
    }

    @Test
    fun `media spec carries the stream url and track metadata`() {
        val spec = toMediaSpec(queueItem(track()), stream())

        assertEquals("asset:///fake_stream.wav", spec.uri)
        assertEquals("Velvet Hours", spec.title)
        assertEquals("Maya Sol", spec.artist)
        assertEquals("Amber Tide", spec.album)
        assertEquals(204_000L, spec.durationMs)
    }

    @Test
    fun `duration falls back to the stream when the track has none`() {
        val spec = toMediaSpec(queueItem(track(durationMs = null)), stream(durationMs = 5_000L))

        assertEquals(5_000L, spec.durationMs)
    }

    @Test
    fun `artist is null when the track has no artists`() {
        val spec = toMediaSpec(queueItem(track(artists = emptyList())), stream())

        assertEquals(null, spec.artist)
    }

    @Test
    fun `placeholder uri round-trips the queue item id`() {
        val uri = queueItemPlaceholderUri("queue-uuid-1")

        assertEquals("rizx://queue/queue-uuid-1", uri)
        assertEquals("queue-uuid-1", queueItemIdFromPlaceholder(uri))
    }

    @Test
    fun `a non-placeholder uri resolves to null`() {
        assertEquals(null, queueItemIdFromPlaceholder("asset:///fake_stream.wav"))
        assertEquals(null, queueItemIdFromPlaceholder("https://example.com/a.mp3"))
    }
}
