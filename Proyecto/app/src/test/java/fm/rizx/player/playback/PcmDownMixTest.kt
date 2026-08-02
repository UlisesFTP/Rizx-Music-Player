package fm.rizx.player.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The down-mix that feeds the waveform and the automatic equalizer.
 *
 * These matter more than they look: the tap now reads the **decoder's** output rather than a buffer the
 * sink already converted to 16-bit for it, so the encodings here are no longer hypothetical. A FLAC with
 * the float output path on arrives as float, and getting that branch wrong is invisible — the bars simply
 * sit still, which is exactly the bug this whole change is about.
 */
@OptIn(UnstableApi::class)
class PcmDownMixTest {

    private fun buffer(bytes: Int, fill: ByteBuffer.() -> Unit): ByteBuffer =
        ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN).apply(fill).apply { flip() }

    private fun collect(
        buffer: ByteBuffer,
        channels: Int,
        encoding: Int,
        stride: Int = 1,
    ): Pair<Boolean, List<Float>> {
        val out = mutableListOf<Float>()
        val read = buffer.forEachMonoSample(channels, encoding, stride) { out += it }
        return read to out
    }

    @Test
    fun `16-bit stereo is averaged to mono and scaled to plus-minus one`() {
        val buf = buffer(8) { putShort(32767); putShort(32767); putShort(-32768); putShort(-32768) }

        val (read, samples) = collect(buf, channels = 2, encoding = C.ENCODING_PCM_16BIT)

        assertTrue(read)
        assertEquals(2, samples.size)
        assertEquals(1f, samples[0], 0.001f)
        assertEquals(-1f, samples[1], 0.001f)
    }

    @Test
    fun `float PCM is read as-is — the encoding a lossless track actually arrives in`() {
        val buf = buffer(16) { putFloat(0.5f); putFloat(0.5f); putFloat(-0.25f); putFloat(-0.75f) }

        val (read, samples) = collect(buf, channels = 2, encoding = C.ENCODING_PCM_FLOAT)

        assertTrue(read)
        assertEquals(listOf(0.5f, -0.5f), samples.map { it })
    }

    @Test
    fun `24-bit mono sign-extends from the top byte`() {
        // 0x7FFFFF = full positive, 0x800000 = full negative, both little-endian.
        val buf = buffer(6) {
            put(0xFF.toByte()); put(0xFF.toByte()); put(0x7F)
            put(0x00); put(0x00); put(0x80.toByte())
        }

        val (read, samples) = collect(buf, channels = 1, encoding = C.ENCODING_PCM_24BIT)

        assertTrue(read)
        assertEquals(1f, samples[0], 0.001f)
        assertEquals(-1f, samples[1], 0.001f)
    }

    @Test
    fun `32-bit mono is scaled by the full int range`() {
        val buf = buffer(8) { putInt(Int.MAX_VALUE); putInt(Int.MIN_VALUE) }

        val (read, samples) = collect(buf, channels = 1, encoding = C.ENCODING_PCM_32BIT)

        assertTrue(read)
        assertEquals(1f, samples[0], 0.001f)
        assertEquals(-1f, samples[1], 0.001f)
    }

    @Test
    fun `stride skips frames without shifting the ones it keeps`() {
        val buf = buffer(8) { putShort(1000); putShort(2000); putShort(3000); putShort(4000) }

        val (_, samples) = collect(buf, channels = 1, encoding = C.ENCODING_PCM_16BIT, stride = 2)

        assertEquals(2, samples.size)
        assertEquals(1000f / 32768f, samples[0], 0.0001f)
        assertEquals(3000f / 32768f, samples[1], 0.0001f)
    }

    @Test
    fun `an encoding it cannot read reports false and consumes nothing`() {
        val buf = buffer(4) { putInt(0) }

        val (read, samples) = collect(buf, channels = 2, encoding = C.ENCODING_AC3)

        assertFalse(read)
        assertTrue(samples.isEmpty())
    }

    @Test
    fun `the source buffer is left exactly where it was found`() {
        val buf = buffer(8) { putShort(1); putShort(2); putShort(3); putShort(4) }
        val position = buf.position()
        val limit = buf.limit()

        collect(buf, channels = 2, encoding = C.ENCODING_PCM_16BIT)

        // A tap that consumed the buffer would silence the speaker it was drawn from.
        assertEquals(position, buf.position())
        assertEquals(limit, buf.limit())
    }
}
