package fm.rizx.player.domain.playback

import fm.rizx.player.domain.model.AudioFormatUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the lossless tag is allowed to say.
 *
 * The rule these enforce is that the badge is a **record, not a prediction**: it appears for a song that
 * has played losslessly and disappears for one that hasn't. Nothing about a lossless index can be shown
 * before the lookup and the header read, and a fallback is silent by design — so a tag that guessed would
 * be wrong precisely on the songs where it mattered.
 */
class NowPlayingFormatTest {

    private val flac = AudioFormatUi(codec = "flac", bitsPerSample = 16, sampleRateHz = 44_100)
    private val opus = AudioFormatUi(codec = "opus", bitrateKbps = 160)

    @Test
    fun `a lossless codec marks the track, with the codec's own name`() {
        val format = NowPlayingFormat()

        format.publish("q1", flac, trackKey = "deezer:1")

        assertEquals(mapOf("deezer:1" to "FLAC"), format.losslessCodecs.value)
    }

    @Test
    fun `a lossy codec marks nothing`() {
        val format = NowPlayingFormat()

        format.publish("q1", opus, trackKey = "yt:abc")

        assertTrue(format.losslessCodecs.value.isEmpty())
    }

    @Test
    fun `falling back to a normal stream withdraws the tag`() {
        val format = NowPlayingFormat()
        format.publish("q1", flac, trackKey = "deezer:1")

        // Same song, played again, and this time the FLAC didn't verify.
        format.publish("q2", opus, trackKey = "deezer:1")

        assertTrue(format.losslessCodecs.value.isEmpty())
    }

    @Test
    fun `a bitrate alone never counts as lossless`() {
        val format = NowPlayingFormat()

        format.publish("q1", AudioFormatUi(codec = "aac", bitrateKbps = 900), trackKey = "yt:big")

        assertTrue(format.losslessCodecs.value.isEmpty())
    }

    @Test
    fun `publishing without a track key still drives the readout`() {
        val format = NowPlayingFormat()

        format.publish("q1", flac)

        assertEquals("q1", format.current.value?.queueItemId)
        assertTrue(format.losslessCodecs.value.isEmpty())
    }

    @Test
    fun `the map is bounded, dropping the oldest verdicts first`() {
        val format = NowPlayingFormat()

        repeat(600) { format.publish("q$it", flac, trackKey = "track:$it") }

        val known = format.losslessCodecs.value
        assertEquals(512, known.size)
        assertNull(known["track:0"])
        assertEquals("FLAC", known["track:599"])
    }

    @Test
    fun `clear drops the readout without forgetting what has played losslessly`() {
        val format = NowPlayingFormat()
        format.publish("q1", flac, trackKey = "deezer:1")

        format.clear()

        assertNull(format.current.value)
        assertEquals(mapOf("deezer:1" to "FLAC"), format.losslessCodecs.value)
    }
}
