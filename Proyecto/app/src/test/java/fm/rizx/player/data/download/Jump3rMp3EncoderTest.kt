package fm.rizx.player.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * The encoder wrapper, against the real jump3r engine — pure JVM, so what runs here is what runs on the
 * phone. These are the tests the library's own `LameEncoder` could never have given us, since it can't
 * even load on Android (javax.sound).
 *
 * "Valid MP3" is asserted structurally: the stream must be MPEG-1 Layer III frames at the requested
 * bitrate and sample rate, parsed from the frame headers themselves — not by trusting the encoder's
 * return codes.
 */
class Jump3rMp3EncoderTest {

    /** [seconds] of a stereo A440 sine at [rate] Hz, interleaved 16-bit. */
    private fun sine(rate: Int, seconds: Int, channels: Int): ShortArray {
        val frames = rate * seconds
        val pcm = ShortArray(frames * channels)
        for (f in 0 until frames) {
            val sample = (sin(2.0 * PI * 440.0 * f / rate) * 12_000).toInt().toShort()
            for (c in 0 until channels) pcm[f * channels + c] = sample
        }
        return pcm
    }

    private fun encode(rate: Int, channels: Int, seconds: Int = 2): ByteArray {
        val out = ByteArrayOutputStream()
        val encoder = Jump3rMp3Encoder(rate, channels)
        val pcm = sine(rate, seconds, channels)
        encoder.encode(pcm, pcm.size / channels, out)
        encoder.finish(out)
        return out.toByteArray()
    }

    /** Parses the first MPEG frame header at or after [from]; returns (bitrateKbps, sampleRate) or null. */
    private fun frameAt(bytes: ByteArray, from: Int): Pair<Int, Int>? {
        var i = from
        while (i < bytes.size - 4) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            // Frame sync: 11 set bits, MPEG-1 (bits 4-3 = 11), Layer III (bits 2-1 = 01).
            if (b0 == 0xFF && (b1 and 0xFE) == 0xFA) {
                val bitrateIndex = (bytes[i + 2].toInt() and 0xF0) ushr 4
                val sampleIndex = (bytes[i + 2].toInt() and 0x0C) ushr 2
                val bitrate = MPEG1_L3_BITRATES.getOrNull(bitrateIndex) ?: return null
                val rate = MPEG1_RATES.getOrNull(sampleIndex) ?: return null
                return bitrate to rate
            }
            i++
        }
        return null
    }

    @Test
    fun `stereo 44_1 kHz comes out as MPEG-1 Layer III at 320 kbps`() {
        val mp3 = encode(rate = 44_100, channels = 2)

        val frame = frameAt(mp3, 0)
        assertTrue("no MPEG frame found", frame != null)
        assertEquals(320, frame!!.first)
        assertEquals(44_100, frame.second)
    }

    @Test
    fun `48 kHz — what Opus decodes at — is encoded natively, no resample`() {
        val mp3 = encode(rate = 48_000, channels = 2)

        val frame = frameAt(mp3, 0)
        assertEquals(48_000, frame!!.second)
        assertEquals(320, frame.first)
    }

    @Test
    fun `mono is accepted`() {
        val mp3 = encode(rate = 44_100, channels = 1)

        assertTrue(frameAt(mp3, 0) != null)
    }

    @Test
    fun `every frame in the stream is 320 CBR, not just the first`() {
        val mp3 = encode(rate = 44_100, channels = 2)

        // Walk frame to frame by each header's own computed length; CBR means they never vary.
        var at = firstSync(mp3)
        var frames = 0
        while (at < mp3.size - 4) {
            val (bitrate, rate) = frameAt(mp3, at) ?: break
            assertEquals(320, bitrate)
            val padding = (mp3[at + 2].toInt() and 0x02) ushr 1
            at += 144_000 * bitrate / rate + padding
            frames++
        }
        assertTrue("expected a couple of seconds of frames, got $frames", frames > 50)
    }

    @Test
    fun `the size is what 320 kbps of this much audio weighs`() {
        val seconds = 2
        val mp3 = encode(rate = 44_100, channels = 2, seconds = seconds)

        val expected = 320_000 / 8 * seconds
        assertTrue(
            "got ${mp3.size} bytes for ~$expected expected",
            mp3.size in (expected * 90 / 100)..(expected * 115 / 100),
        )
    }

    @Test
    fun `chunked encodes across odd buffer boundaries concatenate cleanly`() {
        val out = ByteArrayOutputStream()
        val encoder = Jump3rMp3Encoder(44_100, 2)
        val pcm = sine(44_100, 1, 2)
        // Feed in deliberately awkward slices (not multiples of the internal call size).
        var at = 0
        val step = 3_331 * 2
        while (at < pcm.size) {
            val end = minOf(at + step, pcm.size)
            val slice = pcm.copyOfRange(at, end)
            encoder.encode(slice, slice.size / 2, out)
            at = end
        }
        encoder.finish(out)

        assertTrue(frameAt(out.toByteArray(), 0) != null)
    }

    private fun firstSync(bytes: ByteArray): Int {
        var i = 0
        while (i < bytes.size - 1) {
            if ((bytes[i].toInt() and 0xFF) == 0xFF && (bytes[i + 1].toInt() and 0xFE) == 0xFA) return i
            i++
        }
        return bytes.size
    }

    private companion object {
        /** MPEG-1 Layer III bitrate table (index 0 = free, 15 = bad). */
        val MPEG1_L3_BITRATES = listOf(-1, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        val MPEG1_RATES = listOf(44_100, 48_000, 32_000)
    }
}
