package fm.rizx.player.data.download

import fm.rizx.player.domain.model.SpatialAudioProfile
import fm.rizx.player.playback.spatial.SmartSpatialEngine
import fm.rizx.player.playback.spatial.StereoPcmTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The seam where the spatializer meets the MP3 encoder.
 *
 * The failures worth guarding here are quiet ones: a frame count that drifts (the file ends up longer or
 * shorter than the song), a mono source that arrives as silence in one ear, an orbit that restarts every
 * buffer because the position was not carried, or a render that opens with an untreated second because
 * the effect faded in as if someone had just switched it on.
 */
class SpatialMp3EncoderTest {

    private val rate = 48_000

    /** Records what reached the encoder, so the test can assert on PCM rather than on MP3 bytes. */
    private class CapturingEncoder : Mp3Encoder {
        val frames = mutableListOf<ShortArray>()
        var finished = 0

        override fun encode(pcm: ShortArray, frames: Int, out: OutputStream) {
            this.frames += pcm.copyOf(frames * 2)
        }

        override fun finish(out: OutputStream) {
            finished++
        }
    }

    private fun engine(): StereoPcmTransform = SmartSpatialEngine()

    private fun encoder(
        delegate: CapturingEncoder,
        sourceChannels: Int = 2,
        profile: SpatialAudioProfile = SmartSpatialEngine.DEFAULT_PROFILE,
    ) = SpatialMp3Encoder(engine(), profile, rate, sourceChannels, delegate)

    private fun tone(hz: Double, frames: Int, channels: Int, amplitude: Float = 0.5f) =
        ShortArray(frames * channels) { i ->
            val frame = i / channels
            (amplitude * 32_768f * sin(2 * PI * hz * frame / rate)).toInt().toShort()
        }

    @Test
    fun `every frame in is a frame out`() {
        val delegate = CapturingEncoder()
        val encoder = encoder(delegate)
        val out = ByteArrayOutputStream()

        encoder.encode(tone(440.0, 1024, channels = 2), 1024, out)
        encoder.encode(tone(440.0, 777, channels = 2), 777, out)
        encoder.finish(out)

        assertEquals(listOf(1024 * 2, 777 * 2), delegate.frames.map { it.size })
        assertEquals(1, delegate.finished)
    }

    @Test
    fun `a mono source arrives as two real channels`() {
        // Not a detail: a spatializer has nothing to work with in one channel, so mono is widened here.
        // Getting it wrong is silence in one ear for the whole file.
        val delegate = CapturingEncoder()
        val encoder = encoder(delegate, sourceChannels = 1)

        encoder.encode(tone(440.0, 2048, channels = 1), 2048, ByteArrayOutputStream())

        val produced = delegate.frames.single()
        assertEquals(2048 * 2, produced.size)
        assertTrue("left is silent", produced.filterIndexed { i, _ -> i % 2 == 0 }.any { abs(it.toInt()) > 100 })
        assertTrue("right is silent", produced.filterIndexed { i, _ -> i % 2 == 1 }.any { abs(it.toInt()) > 100 })
    }

    @Test
    fun `the effect is at full strength from the very first sample`() {
        // A render has no listener mid-fade. If the ramp ran, the opening second of every file would be
        // untreated — which is not a gentler effect, it is a mistake at the start of the song.
        val delegate = CapturingEncoder()
        val encoder = encoder(delegate)

        // A tenth of a second, far shorter than the 0.9 s fade-in a ramped engine would use.
        val input = tone(900.0, 4_800, channels = 2)
        encoder.encode(input, 4_800, ByteArrayOutputStream())

        val produced = delegate.frames.single()
        assertTrue("nothing was processed", difference(input, produced) > 0.05f)
    }

    @Test
    fun `the orbit carries across buffers instead of restarting`() {
        // The position handed to the engine is the running frame count. If it were not, every buffer
        // would begin the orbit again and the song would sit in one place, ticking.
        val delegate = CapturingEncoder()
        val encoder = encoder(delegate, profile = SmartSpatialEngine.DEFAULT_PROFILE.copy(orbitPeriodSec = 4f))
        val chunk = tone(900.0, 24_000, channels = 2) // half a second per call

        repeat(4) { encoder.encode(chunk, 24_000, ByteArrayOutputStream()) }

        // Same input four times: identical output would mean the orbit reset with each call.
        val first = delegate.frames[0]
        val third = delegate.frames[2]
        assertTrue("the orbit restarts every buffer", difference(first, third) > 0.02f)
    }

    @Test
    fun `it is deterministic, so the same song renders to the same bytes`() {
        fun render(): ShortArray {
            val delegate = CapturingEncoder()
            val encoder = encoder(delegate)
            encoder.encode(tone(700.0, 8_192, channels = 2), 8_192, ByteArrayOutputStream())
            return delegate.frames.single()
        }
        assertTrue(render().contentEquals(render()))
    }

    /** RMS difference between two equal-length PCM blocks, in full-scale units. */
    private fun difference(a: ShortArray, b: ShortArray): Float {
        var sum = 0.0
        for (i in a.indices) {
            val d = (a[i] - b[i]) / 32_768.0
            sum += d * d
        }
        return sqrt(sum / a.size).toFloat()
    }
}
