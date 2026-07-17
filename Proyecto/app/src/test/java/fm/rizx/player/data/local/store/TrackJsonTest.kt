package fm.rizx.player.data.local.store

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackJsonTest {

    private fun track() = Track(
        title = "Velvet Hours",
        artists = listOf(ArtistCredit(name = "Maya Sol", source = ProviderRef("meta", "ar-maya"))),
        album = AlbumRef(title = "Amber Tide", source = ProviderRef("meta", "al-amber")),
        durationMs = 204_000,
        source = ProviderRef("meta", "tr-velvet"),
    )

    @Test
    fun `track round-trips through json`() {
        val original = track()

        val decoded = TrackJson.decodeTrack(TrackJson.encodeTrack(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `encoding strips resolution state so stream urls are never persisted`() {
        val withStream = track().copy(
            streamCandidates = listOf(
                StreamCandidate(
                    id = "c1",
                    title = "Velvet Hours",
                    stream = Stream(url = "https://secret/ephemeral.m4a", protocol = StreamProtocol.HTTPS, source = ProviderRef("s", "c1")),
                    source = ProviderRef("s", "c1"),
                ),
            ),
        )

        val encoded = TrackJson.encodeTrack(withStream)
        val decoded = TrackJson.decodeTrack(encoded)

        assertTrue(decoded.streamCandidates.isEmpty())
        assertTrue("stream url must not be persisted", !encoded.contains("ephemeral"))
    }
}
