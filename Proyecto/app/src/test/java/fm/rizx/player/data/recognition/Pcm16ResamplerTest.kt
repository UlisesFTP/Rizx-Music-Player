package fm.rizx.player.data.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The resampler's job is not "produce 16 kHz audio" — nearest-neighbour decimation does that too. It
 * is to produce 16 kHz audio *without inventing tones*, because anything the fingerprint sees at
 * 250–5500 Hz it will encode as a peak whether a musician played it or a bad filter folded it down
 * there. So the load-bearing test here is the alias one.
 */
class Pcm16ResamplerTest {

    private val resampler = Pcm16Resampler()

    @Test
    fun `audio already in the target shape is passed straight through`() {
        val audio = tone(hz = 440.0, rateHz = 16_000, seconds = 0.5)
        assertSame(audio, resampler.toMono16k(audio, inputRateHz = 16_000, channelCount = 1))
    }

    @Test
    fun `rejects a half sample`() {
        val error = runCatching { resampler.toMono16k(ByteArray(101), 44_100, 1) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `rejects an empty input`() {
        val error = runCatching { resampler.toMono16k(ByteArray(0), 44_100, 1) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `output length follows the rate ratio`() {
        val seconds = 1.0
        val out = resampler.toMono16k(tone(1_000.0, 44_100, seconds), inputRateHz = 44_100, channelCount = 1)
        val samples = out.size / 2
        assertEquals(16_000.0, samples.toDouble(), 32.0)
    }

    @Test
    fun `stereo is averaged down to one channel`() {
        val seconds = 0.5
        val stereo = tone(1_000.0, 44_100, seconds, channels = 2)
        val out = resampler.toMono16k(stereo, inputRateHz = 44_100, channelCount = 2)
        assertEquals(16_000.0 * seconds, (out.size / 2).toDouble(), 32.0)
    }

    @Test
    fun `a tone keeps its pitch across the rate change`() {
        val out = resampler.toMono16k(tone(1_000.0, 44_100, 1.0), inputRateHz = 44_100, channelCount = 1)
        // 16 kHz over 2048 bins puts 1 kHz at bin 128.
        assertEquals(128, loudestBin(out))
    }

    @Test
    fun `content above the new nyquist is filtered out, not folded back into the music`() {
        // 10 kHz cannot exist at 16 kHz. Naive decimation mirrors it to 6 kHz — bin 768 — and the
        // fingerprint would encode a peak nobody played.
        val aliased = resampler.toMono16k(tone(10_000.0, 44_100, 1.0), inputRateHz = 44_100, channelCount = 1)
        val genuine = resampler.toMono16k(tone(6_000.0, 44_100, 1.0), inputRateHz = 44_100, channelCount = 1)

        val ghost = energyAt(aliased, bin = 768)
        val real = energyAt(genuine, bin = 768)
        assertTrue("alias energy $ghost vs genuine $real", ghost < real / 1_000.0)
    }

    // -- helpers --------------------------------------------------------------------------------

    private fun tone(hz: Double, rateHz: Int, seconds: Double, channels: Int = 1): ByteArray {
        val frames = (rateHz * seconds).toInt()
        val buffer = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { i ->
            val value = (0.8 * sin(2 * PI * hz * i / rateHz) * Short.MAX_VALUE).toInt().toShort()
            repeat(channels) { buffer.putShort(value) }
        }
        return buffer.array()
    }

    /** Hann-windowed spectrum of the first 2048 samples, reusing the generator's own transform. */
    private fun spectrum(pcm16: ByteArray): DoubleArray {
        val shorts = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val windowed = DoubleArray(2048) { i ->
            shorts.get(i + 4096).toDouble() * (0.5 * (1.0 - cos(2 * PI * i / 2047.0)))
        }
        return ShazamSignatureGenerator.magnitudes(windowed)
    }

    private fun loudestBin(pcm16: ByteArray): Int {
        val bins = spectrum(pcm16)
        return bins.indices.maxByOrNull { bins[it] } ?: -1
    }

    /** Energy around [bin], widened a little so a one-bin drift doesn't read as silence. */
    private fun energyAt(pcm16: ByteArray, bin: Int): Double {
        val bins = spectrum(pcm16)
        return (bin - 3..bin + 3).sumOf { bins[it] }
    }
}
