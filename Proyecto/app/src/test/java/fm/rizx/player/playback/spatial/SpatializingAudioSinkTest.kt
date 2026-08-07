package fm.rizx.player.playback.spatial

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import fm.rizx.player.domain.model.SpatialAudioProfile
import fm.rizx.player.domain.model.SpatialInactiveReason
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The buffer contract, which is where this feature can break playback for everything else.
 *
 * `AudioSink.handleBuffer` may take a buffer in pieces, and it hands the *same* buffer back until it is
 * empty. Two mistakes are possible and both are immediately audible: run the DSP again on a retry and
 * the delay lines hear the same audio twice, so the song stutters; advance the caller's position by
 * more than was actually taken and whole slices of the song are dropped. Neither throws, so only a test
 * like this one catches them.
 */
@OptIn(UnstableApi::class)
class SpatializingAudioSinkTest {

    private val state = SpatialSinkState()

    /** Records what the DSP was asked to do, and can scale the audio so the transform is visible. */
    private class CountingEngine(private val gain: Float = 1f) : StereoPcmTransform {
        var processCalls = 0
        var framesSeen = 0
        var resets = 0
        var configuredRate = 0
        var running = true

        override fun configure(sampleRateHz: Int) { configuredRate = sampleRateHz }
        override fun setProfile(profile: SpatialAudioProfile) = Unit
        override fun setEnabled(enabled: Boolean, ramp: Boolean) { running = enabled }
        override fun reset(positionUs: Long) { resets++ }
        override val silent: Boolean get() = !running

        override fun process(frames: FloatArray, frameCount: Int, positionUs: Long) {
            processCalls++
            framesSeen += frameCount
            if (gain != 1f) for (i in 0 until frameCount * 2) frames[i] *= gain
        }
    }

    /**
     * A delegate that accepts only as much as its script allows, in order — the shapes a real sink
     * produces when the AudioTrack is nearly full.
     */
    private class ScriptedSink(private vararg val allowances: Int) {
        val received = ByteArrayOutputStream()
        var calls = 0
        val timestamps = mutableListOf<Long>()

        val mock: AudioSink = mockk(relaxed = true) {
            every { handleBuffer(any(), any(), any()) } answers {
                val buffer = firstArg<ByteBuffer>()
                timestamps += secondArg<Long>()
                val allowance = allowances.getOrElse(calls) { Int.MAX_VALUE }
                calls++
                val take = minOf(buffer.remaining(), allowance)
                val copy = ByteArray(take)
                buffer.duplicate().get(copy, 0, take)
                received.write(copy)
                buffer.position(buffer.position() + take)
                !buffer.hasRemaining()
            }
        }
    }

    private fun format(
        encoding: Int = C.ENCODING_PCM_16BIT,
        channels: Int = 2,
        sampleRate: Int = 48_000,
    ): Format = Format.Builder()
        .setSampleRate(sampleRate)
        .setChannelCount(channels)
        .setPcmEncoding(encoding)
        .build()

    private fun pcm16(shorts: ShortArray): ByteBuffer =
        ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            shorts.forEach { putShort(it) }
            flip()
        }

    private fun ramp(frames: Int): ShortArray = ShortArray(frames * 2) { (it * 7 - 1000).toShort() }

    // -- consumption -----------------------------------------------------------------------------

    @Test
    fun `when the delegate takes everything, the input is fully consumed exactly once`() {
        val engine = CountingEngine()
        val delegate = ScriptedSink()
        val sink = SpatializingAudioSink(delegate.mock, engine, state)
        sink.configure(format(), 0, null)

        val input = pcm16(ramp(256))
        val handled = sink.handleBuffer(input, 0L, 1)

        assertTrue(handled)
        assertFalse(input.hasRemaining())
        assertEquals(1, engine.processCalls)
        assertEquals(256, engine.framesSeen)
        assertEquals(1024, delegate.received.size())
    }

    @Test
    fun `a half-consumed buffer is finished on the retry without being processed twice`() {
        val engine = CountingEngine()
        val delegate = ScriptedSink(512) // first call takes half of a 1024-byte buffer
        val sink = SpatializingAudioSink(delegate.mock, engine, state)
        sink.configure(format(), 0, null)

        val input = pcm16(ramp(256))
        val first = sink.handleBuffer(input, 0L, 1)

        assertFalse("a partly-taken buffer is not handled", first)
        assertEquals("the caller must be advanced by exactly what was taken", 512, input.position())

        val second = sink.handleBuffer(input, 0L, 1)

        assertTrue(second)
        assertFalse(input.hasRemaining())
        assertEquals("the DSP ran twice over the same audio", 1, engine.processCalls)
        assertEquals(1024, delegate.received.size())
    }

    @Test
    fun `a delegate that takes nothing loses nothing`() {
        val engine = CountingEngine()
        val delegate = ScriptedSink(0, 0)
        val sink = SpatializingAudioSink(delegate.mock, engine, state)
        sink.configure(format(), 0, null)

        val input = pcm16(ramp(128))
        assertFalse(sink.handleBuffer(input, 0L, 1))
        assertEquals(0, input.position())
        assertFalse(sink.handleBuffer(input, 0L, 1))
        assertEquals(0, input.position())
        assertTrue(sink.handleBuffer(input, 0L, 1))

        assertFalse(input.hasRemaining())
        assertEquals(1, engine.processCalls)
        assertEquals(512, delegate.received.size())
    }

    @Test
    fun `dribbled out in odd-sized pieces, the audio arrives whole and in order`() {
        val engine = CountingEngine()
        val delegate = ScriptedSink(7, 100, 3, 250, 0, 1)
        val sink = SpatializingAudioSink(delegate.mock, engine, state)
        sink.configure(format(), 0, null)

        val shorts = ramp(256)
        val input = pcm16(shorts)
        var guard = 0
        while (input.hasRemaining() && guard++ < 50) sink.handleBuffer(input, 0L, 1)

        assertFalse(input.hasRemaining())
        assertEquals(1, engine.processCalls)
        // Identity DSP, so what came out the far end must be the same bytes that went in.
        assertArrayEquals(pcm16(shorts).let { ByteArray(it.remaining()).also { b -> it.get(b) } }, delegate.received.toByteArray())
    }

    @Test
    fun `the presentation timestamp is passed through untouched`() {
        val delegate = ScriptedSink(512)
        val sink = SpatializingAudioSink(delegate.mock, CountingEngine(), state)
        sink.configure(format(), 0, null)

        val input = pcm16(ramp(256))
        sink.handleBuffer(input, 1_234_567L, 1)
        sink.handleBuffer(input, 1_234_567L, 1)

        assertTrue(delegate.timestamps.all { it == 1_234_567L })
    }

    // -- bypass ----------------------------------------------------------------------------------

    @Test
    fun `a silent engine hands the delegate the caller's own buffer, uncopied`() {
        val engine = CountingEngine().apply { running = false }
        var seen: ByteBuffer? = null
        val delegate = mockk<AudioSink>(relaxed = true) {
            every { handleBuffer(any(), any(), any()) } answers {
                seen = firstArg()
                firstArg<ByteBuffer>().position(firstArg<ByteBuffer>().limit())
                true
            }
        }
        val sink = SpatializingAudioSink(delegate, engine, state)
        sink.configure(format(), 0, null)

        val input = pcm16(ramp(64))
        sink.handleBuffer(input, 0L, 1)

        assertSame("bypass must not copy", input, seen)
        assertEquals(0, engine.processCalls)
    }

    @Test
    fun `mono is left alone, and says why`() {
        val engine = CountingEngine()
        val delegate = ScriptedSink()
        val sink = SpatializingAudioSink(delegate.mock, engine, state)

        sink.configure(format(channels = 1), 0, null)
        sink.handleBuffer(pcm16(ShortArray(128)), 0L, 1)

        assertFalse(state.supported)
        assertEquals(SpatialInactiveReason.UNSUPPORTED_CHANNEL_LAYOUT, state.reason)
        assertEquals(0, engine.processCalls)
    }

    @Test
    fun `surround is left alone`() {
        val engine = CountingEngine()
        val sink = SpatializingAudioSink(ScriptedSink().mock, engine, state)

        sink.configure(format(channels = 6), 0, null)
        sink.handleBuffer(pcm16(ShortArray(600)), 0L, 1)

        assertEquals(SpatialInactiveReason.UNSUPPORTED_CHANNEL_LAYOUT, state.reason)
        assertEquals(0, engine.processCalls)
    }

    @Test
    fun `an encoding it cannot decode is left alone, and says why`() {
        val engine = CountingEngine()
        val sink = SpatializingAudioSink(ScriptedSink().mock, engine, state)

        sink.configure(format(encoding = C.ENCODING_PCM_8BIT), 0, null)
        sink.handleBuffer(pcm16(ShortArray(128)), 0L, 1)

        assertFalse(state.supported)
        assertEquals(SpatialInactiveReason.UNSUPPORTED_PCM, state.reason)
        assertEquals(0, engine.processCalls)
    }

    @Test
    fun `a buffer that is not a whole number of frames passes through rather than being mangled`() {
        val engine = CountingEngine()
        val delegate = ScriptedSink()
        val sink = SpatializingAudioSink(delegate.mock, engine, state)
        sink.configure(format(), 0, null)

        // 5 bytes cannot be split into 4-byte stereo frames.
        val odd = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).apply { position(0); limit(5) }
        sink.handleBuffer(odd, 0L, 1)

        assertEquals(0, engine.processCalls)
        assertEquals(5, delegate.received.size())
    }

    // -- format handling and lifecycle -----------------------------------------------------------

    @Test
    fun `every supported encoding survives a round trip untouched`() {
        // Audio that merely passes through the effect must come out bit-identical. That is why the
        // codec scales by the same power of two in both directions: the more usual `2^n − 1` on the way
        // back loses the bottom bit of every sample, which is a dither nobody asked for.
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_FLOAT)) {
            val engine = CountingEngine()
            val delegate = ScriptedSink()
            val sink = SpatializingAudioSink(delegate.mock, engine, state)
            sink.configure(format(encoding = encoding), 0, null)

            // Start from real samples and encode them, rather than from arbitrary bytes — as floats,
            // random bytes are mostly denormals and infinities, which say nothing about audio.
            val samples = FloatArray(128) { (it % 64 - 32) / 33f }
            val bytes = samples.size * PcmFrameCodec.bytesPerSample(encoding)
            val encoded = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
            PcmFrameCodec.write(samples, samples.size, encoded, encoding)
            val source = ByteArray(bytes).also { encoded.duplicate().get(it) }

            sink.handleBuffer(ByteBuffer.wrap(source.copyOf()).order(ByteOrder.LITTLE_ENDIAN), 0L, 1)

            assertEquals("encoding $encoding was not processed", 1, engine.processCalls)
            assertArrayEquals("encoding $encoding did not round-trip", source, delegate.received.toByteArray())
        }
    }

    @Test
    fun `32-bit pcm passes through within a hair of itself`() {
        // The one encoding that cannot be exact: a 32-bit sample has more bits than a Float's mantissa,
        // so the trip through the DSP's float domain necessarily rounds. It must still be inaudible.
        val engine = CountingEngine()
        val delegate = ScriptedSink()
        val sink = SpatializingAudioSink(delegate.mock, engine, state)
        sink.configure(format(encoding = C.ENCODING_PCM_32BIT), 0, null)

        val samples = FloatArray(64) { (it % 32 - 16) / 17f }
        val encoded = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        PcmFrameCodec.write(samples, samples.size, encoded, C.ENCODING_PCM_32BIT)
        val source = ByteArray(samples.size * 4).also { encoded.duplicate().get(it) }

        sink.handleBuffer(ByteBuffer.wrap(source.copyOf()).order(ByteOrder.LITTLE_ENDIAN), 0L, 1)

        val out = ByteBuffer.wrap(delegate.received.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        val original = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
        for (i in samples.indices) {
            val drift = kotlin.math.abs(out.getInt(i * 4).toLong() - original.getInt(i * 4).toLong())
            assertTrue("sample $i drifted by $drift", drift < 512L)
        }
    }

    @Test
    fun `the engine is told the stream's sample rate`() {
        val engine = CountingEngine()
        val sink = SpatializingAudioSink(ScriptedSink().mock, engine, state)
        sink.configure(format(sampleRate = 44_100), 0, null)
        assertEquals(44_100, engine.configuredRate)
    }

    @Test
    fun `flushing drops the half-delivered buffer and resets the engine`() {
        val engine = CountingEngine()
        val delegate = ScriptedSink(512)
        val sink = SpatializingAudioSink(delegate.mock, engine, state)
        sink.configure(format(), 0, null)

        val input = pcm16(ramp(256))
        sink.handleBuffer(input, 0L, 1)
        sink.flush()

        val resetsAfterFlush = engine.resets
        assertTrue("the seek did not reset the DSP", resetsAfterFlush > 0)

        // A new song's first buffer must be processed fresh, not mistaken for the retry of the old one.
        val next = pcm16(ramp(64))
        sink.handleBuffer(next, 0L, 1)
        assertEquals(2, engine.processCalls)
    }
}
