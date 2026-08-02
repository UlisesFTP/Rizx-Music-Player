package fm.rizx.player.data.lossless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is the one place in this feature where a wrong answer is silent: every number it returns is
 * shown to the user as measured fact, and a misread offset produces plausible-looking nonsense rather
 * than an error. So the fixtures are built bit by bit here rather than taken from a real file — a real
 * file would only ever prove the one layout it happens to have.
 *
 * The 16/44.1 and 24/96 cases are the shapes that actually occur; the rest are the ways the bytes can lie.
 */
class FlacStreamInfoParserTest {

    @Test
    fun `reads a 16-bit 44_1 kHz stereo header`() {
        val info = FlacStreamInfoParser.parse(flac(sampleRate = 44_100, channels = 2, bits = 16))!!

        assertEquals(44_100, info.sampleRateHz)
        assertEquals(16, info.bitsPerSample)
        assertEquals(2, info.channels)
    }

    @Test
    fun `reads a 24-bit 96 kHz stereo header`() {
        // Every field here crosses a byte boundary, so this is the case that catches a shift being off
        // by one: 96000 needs 17 of its 20 bits, and 24-bit splits across bytes 12 and 13.
        val info = FlacStreamInfoParser.parse(flac(sampleRate = 96_000, channels = 2, bits = 24))!!

        assertEquals(96_000, info.sampleRateHz)
        assertEquals(24, info.bitsPerSample)
        assertEquals(2, info.channels)
    }

    @Test
    fun `reads the real-world 16-bit 48 kHz stereo shape the reference index serves`() {
        // Measured live against the index this was built for: 48 kHz rather than CD's 44.1. The parser
        // must not "helpfully" normalise that — it is a fact about the file and possibly a clue.
        val info = FlacStreamInfoParser.parse(
            flac(sampleRate = 48_000, channels = 2, bits = 16, totalSamples = 11_392_533L),
        )!!

        assertEquals(48_000, info.sampleRateHz)
        assertEquals(11_392_533L, info.totalSamples)
        assertEquals(237_344L, info.durationMs)
    }

    @Test
    fun `reads mono`() {
        assertEquals(1, FlacStreamInfoParser.parse(flac(channels = 1))!!.channels)
    }

    @Test
    fun `reads a multichannel header`() {
        assertEquals(6, FlacStreamInfoParser.parse(flac(channels = 6))!!.channels)
    }

    @Test
    fun `computes duration from total samples over sample rate`() {
        val info = FlacStreamInfoParser.parse(
            flac(sampleRate = 44_100, totalSamples = 44_100L * 185),
        )!!

        assertEquals(185_000L, info.durationMs)
    }

    @Test
    fun `total samples is a 36-bit field and must not overflow an Int`() {
        // 5e9 needs 33 bits. Read as an Int this comes back negative, the duration goes negative, and a
        // perfectly good file is rejected as "no duration" — with nothing in the logs to say why.
        val info = FlacStreamInfoParser.parse(
            flac(sampleRate = 48_000, totalSamples = 5_000_000_000L),
        )!!

        assertEquals(5_000_000_000L, info.totalSamples)
        assertTrue(info.durationMs > 0L)
    }

    @Test
    fun `reads the STREAMINFO MD5`() {
        val md5 = ByteArray(16) { (it + 1).toByte() }
        val info = FlacStreamInfoParser.parse(flac(md5 = md5))!!

        assertEquals("0102030405060708090a0b0c0d0e0f10", info.streamInfoMd5Hex)
    }

    @Test
    fun `an all-zero MD5 is absence, not a value`() {
        // The spec's way of saying "the encoder didn't record one". Reporting 32 zeros as a checksum
        // would invite comparing it against another file's 32 zeros.
        assertNull(FlacStreamInfoParser.parse(flac(md5 = ByteArray(16)))!!.streamInfoMd5Hex)
    }

    @Test
    fun `finds STREAMINFO even when another block is written first`() {
        // The spec requires it first; tolerating a file that disagrees costs one loop.
        val info = FlacStreamInfoParser.parse(flac(leadingPaddingBytes = 32))!!

        assertEquals(44_100, info.sampleRateHz)
    }

    @Test
    fun `rejects bytes that are not a FLAC bitstream`() {
        val mp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x44) + ByteArray(200)

        assertNull(FlacStreamInfoParser.parse(mp3))
    }

    @Test
    fun `rejects a buffer too short to hold the magic`() {
        assertNull(FlacStreamInfoParser.parse(byteArrayOf(0x66, 0x4C)))
    }

    @Test
    fun `rejects a truncated STREAMINFO block`() {
        // The download died mid-header. Half a block parses into whatever follows it in memory.
        val full = flac()

        assertNull(FlacStreamInfoParser.parse(full.copyOf(full.size - 10)))
    }

    @Test
    fun `rejects a block that declares the wrong STREAMINFO length`() {
        assertNull(FlacStreamInfoParser.parse(flac(declaredBlockLength = 20)))
    }

    @Test
    fun `rejects a sample rate below the valid range`() {
        assertNull(FlacStreamInfoParser.parse(flac(sampleRate = 4_000)))
    }

    @Test
    fun `rejects a sample rate above the valid range`() {
        assertNull(FlacStreamInfoParser.parse(flac(sampleRate = 900_000)))
    }

    @Test
    fun `rejects an impossible bit depth`() {
        assertNull(FlacStreamInfoParser.parse(flac(bits = 2)))
    }

    @Test
    fun `rejects a stream that declares no samples`() {
        // Legal FLAC ("length unknown"), useless here: with a minimal index the duration is the only
        // thing that separates this recording from another one with the same title.
        assertNull(FlacStreamInfoParser.parse(flac(totalSamples = 0L)))
    }

    @Test
    fun `rejects a header whose last-block flag is set before any STREAMINFO`() {
        assertNull(FlacStreamInfoParser.parse(flac(omitStreamInfo = true)))
    }

    private fun flac(
        sampleRate: Int = 44_100,
        channels: Int = 2,
        bits: Int = 16,
        totalSamples: Long = 44_100L * 180,
        md5: ByteArray = ByteArray(16) { 0x11 },
        declaredBlockLength: Int = 34,
        leadingPaddingBytes: Int = 0,
        omitStreamInfo: Boolean = false,
    ): ByteArray = FlacFixtures.head(
        sampleRate = sampleRate,
        channels = channels,
        bits = bits,
        totalSamples = totalSamples,
        md5 = md5,
        declaredBlockLength = declaredBlockLength,
        leadingPaddingBytes = leadingPaddingBytes,
        omitStreamInfo = omitStreamInfo,
    )
}
