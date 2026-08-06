package fm.rizx.player.data.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

/**
 * The fingerprint is the one part of recognition that fails *silently* when it is wrong: a malformed
 * signature is still a well-formed HTTP request, and the service answers it with an empty match list
 * exactly as it would for a recording it genuinely doesn't know. Nothing crashes, nothing logs, the
 * feature is simply always wrong.
 *
 * So these tests hold the wire format itself — magic numbers, checksum placement, band framing,
 * sample accounting — rather than only checking that some string comes back. Audio here is
 * synthesised (tones, chirps, seeded noise): deterministic, tiny, and no commercial recording enters
 * the repository.
 */
class ShazamSignatureGeneratorTest {

    private val generator = ShazamSignatureGenerator()

    @Test
    fun `rejects an empty input`() {
        val error = runCatching { generator.generate(ByteArray(0)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `rejects a half sample`() {
        val error = runCatching { generator.generate(ByteArray(4097)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `rejects an implausibly large input`() {
        // 20 s of 16 kHz mono — past the cap, and the shape a bug upstream would produce.
        val error = runCatching { generator.generate(ByteArray(16_000 * 2 * 20)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `the same audio always produces the same signature`() {
        val audio = tone(seconds = 6.0)
        assertEquals(generator.generate(audio), generator.generate(audio))
        // A fresh generator too: no state may survive between calls.
        assertEquals(generator.generate(audio), ShazamSignatureGenerator().generate(audio))
    }

    @Test
    fun `different audio produces different signatures`() {
        assertNotEquals(
            generator.generate(tone(seconds = 6.0, hz = 440.0)),
            generator.generate(tone(seconds = 6.0, hz = 1300.0)),
        )
    }

    @Test
    fun `carries the mime prefix the service expects`() {
        val signature = generator.generate(tone(seconds = 6.0))
        assertTrue(signature.startsWith("data:audio/vnd.shazam.sig;base64,"))
        // The payload must survive a strict decoder — NO_WRAP equivalent, no line breaks smuggled in.
        Base64.getDecoder().decode(signature.removePrefix(ShazamSignatureGenerator.SIGNATURE_URI_PREFIX))
    }

    @Test
    fun `header carries the magic numbers, the sample rate and a valid checksum`() {
        val decoded = decode(generator.generate(tone(seconds = 6.0)))
        val header = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(0xcafe2580.toInt(), header.getInt(0))
        assertEquals(0x94119c00.toInt(), header.getInt(12))
        assertEquals(3 shl 27, header.getInt(28))
        assertEquals(0x40000000, header.getInt(48))

        val checksum = CRC32().apply { update(decoded, 8, decoded.size - 8) }.value.toInt()
        assertEquals(checksum, header.getInt(4))
    }

    @Test
    fun `the declared sizes match the bytes actually sent`() {
        val decoded = decode(generator.generate(tone(seconds = 6.0)))
        val header = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN)

        val bodySize = decoded.size - HEADER_AND_MARKER
        assertEquals(bodySize + 8, header.getInt(8))
        assertEquals(bodySize + 8, header.getInt(52))
    }

    @Test
    fun `sample count reflects the audio consumed`() {
        val seconds = 6.0
        val decoded = decode(generator.generate(tone(seconds = seconds)))
        val declared = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN).getInt(40)

        // Whole 128-sample hops only, plus the fixed 0.24 s offset the format carries.
        val consumed = ((16_000 * seconds).toInt() / 128) * 128
        assertEquals(consumed + (16_000 * 0.24).toInt(), declared)
    }

    @Test
    fun `stops consuming audio once it has enough peaks`() {
        // 15 s of dense audio: past the 12 s ceiling, and rich enough to reach the peak cap early.
        val decoded = decode(generator.generate(chirp(seconds = 15.0)))
        val declared = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN).getInt(40)

        val consumedSeconds = (declared - (16_000 * 0.24)) / 16_000.0
        assertTrue("consumed ${consumedSeconds}s, expected the 12 s ceiling to hold", consumedSeconds <= 12.5)
    }

    @Test
    fun `music produces peaks and silence produces none`() {
        assertTrue(bands(decode(generator.generate(chirp(seconds = 8.0)))).isNotEmpty())
        assertTrue(bands(decode(generator.generate(ByteArray(16_000 * 2 * 8)))).isEmpty())
    }

    @Test
    fun `every band block is tagged and padded to a four byte boundary`() {
        val decoded = decode(generator.generate(chirp(seconds = 8.0)))
        var offset = HEADER_AND_MARKER
        val buffer = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN)

        while (offset < decoded.size) {
            val marker = buffer.getInt(offset)
            assertTrue("unexpected band marker ${marker.toUInt().toString(16)}", marker in 0x60030040..0x60030043)
            val size = buffer.getInt(offset + 4)
            assertTrue("band of $size bytes", size > 0)
            offset += 8 + size + (4 - size % 4) % 4
        }
        assertEquals(decoded.size, offset)
    }

    // -- synthetic audio ------------------------------------------------------------------------

    private fun decode(signature: String): ByteArray =
        Base64.getDecoder().decode(signature.removePrefix(ShazamSignatureGenerator.SIGNATURE_URI_PREFIX))

    /** The band markers present in an encoded signature, in order. */
    private fun bands(decoded: ByteArray): List<Int> {
        val buffer = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN)
        val found = mutableListOf<Int>()
        var offset = HEADER_AND_MARKER
        while (offset < decoded.size) {
            found += buffer.getInt(offset)
            val size = buffer.getInt(offset + 4)
            offset += 8 + size + (4 - size % 4) % 4
        }
        return found
    }

    /** A steady tone with a little seeded noise, so the spectrum has one obvious peak to find. */
    private fun tone(seconds: Double, hz: Double = 440.0): ByteArray =
        pcm(seconds) { t, noise -> 0.6 * sin(2 * PI * hz * t) + 0.02 * noise }

    /** A sweep across the fingerprinted bands, which yields peaks in all four of them. */
    private fun chirp(seconds: Double): ByteArray =
        pcm(seconds) { t, noise ->
            val sweep = 300.0 + (5000.0 * (t % 2.0) / 2.0)
            0.5 * sin(2 * PI * sweep * t) + 0.3 * sin(2 * PI * 900.0 * t) + 0.05 * noise
        }

    private fun pcm(seconds: Double, sample: (t: Double, noise: Double) -> Double): ByteArray {
        val count = (16_000 * seconds).toInt()
        val buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        var seed = 0x5DEECE66DL // seeded, so "noise" is identical on every run
        repeat(count) { i ->
            seed = (seed * 25214903917L + 11L) and 0xFFFFFFFFFFFFL
            val noise = (seed shr 16).toInt() / Int.MAX_VALUE.toDouble()
            val value = sample(i / 16_000.0, noise).coerceIn(-1.0, 1.0)
            buffer.putShort((value * Short.MAX_VALUE).toInt().toShort())
        }
        return buffer.array()
    }

    private companion object {
        /** 48-byte header plus the 8-byte contents marker that precedes the band blocks. */
        const val HEADER_AND_MARKER = 56
    }
}
