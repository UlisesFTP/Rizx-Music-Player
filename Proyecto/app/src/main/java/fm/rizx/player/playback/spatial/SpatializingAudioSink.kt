package fm.rizx.player.playback.spatial

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import fm.rizx.player.domain.model.SpatialInactiveReason
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the current stream lets the spatializer do. Written by the audio thread, read by the controller.
 *
 * `@Volatile` and nothing more: two independent fields that are each a single word, never read as a
 * pair for a decision. A lock here would be a lock in the audio callback.
 */
@UnstableApi
class SpatialSinkState {
    @Volatile
    var supported: Boolean = true
        internal set

    @Volatile
    var reason: SpatialInactiveReason = SpatialInactiveReason.NONE
        internal set
}

/**
 * Runs the spatializer on every PCM buffer on its way to the real sink.
 *
 * **Why this wraps the sink instead of being an `AudioProcessor`.** Because a processor would be
 * silently dropped for exactly the songs this matters most for. `DefaultAudioSink.configure` builds its
 * chain from a fixed list whenever the input is high-resolution, so anything handed to
 * `setAudioProcessors` disappears on the float path — the path "prefer lossless" turns on. That already
 * cost this app a frozen waveform and a deaf automatic equaliser on FLAC; see [PcmTappingAudioSink],
 * which is wrapped around *this* so the analysis still sees the recording rather than the effect.
 *
 * **The delicate part is not the DSP, it is the buffer contract.** A sink that cannot take a whole
 * buffer is handed the same one again, and the caller's position is advanced only by what was actually
 * consumed. Get that wrong in either direction and the result is audible immediately: process twice and
 * the song stutters, advance too far and pieces of it go missing. So:
 *
 * - each input buffer is processed **once**, into a buffer of our own;
 * - the renderer's buffer is never written to;
 * - after the delegate takes some of our output, the input is advanced by exactly that many bytes,
 *   which is a one-to-one mapping because the encoding and the channel count never change here;
 * - with the effect off, the original buffer is passed straight through — no copy, no conversion, no
 *   allocation.
 */
@UnstableApi
internal class SpatializingAudioSink(
    sink: AudioSink,
    private val engine: StereoPcmTransform,
    private val state: SpatialSinkState,
) : ForwardingAudioSink(sink) {

    private var encoding = Format.NO_VALUE
    private var channelCount = 0
    private var frameBytes = 0
    private var supported = false

    private var samples = FloatArray(0)
    private var output: ByteBuffer? = null

    /** Non-null while a processed buffer is still being drained by the delegate. */
    private var pending: ByteBuffer? = null
    private var pendingTimeUs = Long.MIN_VALUE

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        encoding = inputFormat.pcmEncoding
        channelCount = inputFormat.channelCount
        val bytesPerSample = PcmFrameCodec.bytesPerSample(encoding)
        frameBytes = bytesPerSample * channelCount

        // v1 spatializes plain stereo. Turning mono into stereo would mean handing the delegate twice
        // the bytes it was configured for and rewriting the format underneath it — a real risk for a
        // rare case, so mono simply passes through untouched.
        supported = channelCount == 2 && bytesPerSample > 0
        state.supported = supported
        state.reason = when {
            supported -> SpatialInactiveReason.NONE
            channelCount != 2 -> SpatialInactiveReason.UNSUPPORTED_CHANNEL_LAYOUT
            else -> SpatialInactiveReason.UNSUPPORTED_PCM
        }
        if (supported) engine.configure(inputFormat.sampleRate)
        discardPending()
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val held = pending
        if (held != null && held.hasRemaining() && presentationTimeUs == pendingTimeUs) {
            // A retry of a buffer we already spatialized. Running the DSP over it again would push the
            // same audio through the delay lines twice, and the song would stutter.
            return drain(held, buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        discardPending()

        if (!supported || engine.silent) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        val processed = process(buffer, presentationTimeUs)
            ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        return drain(processed, buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    /** Hands our output down and mirrors whatever the delegate took onto the caller's buffer. */
    private fun drain(
        processed: ByteBuffer,
        input: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val before = processed.position()
        val handled = super.handleBuffer(processed, presentationTimeUs, encodedAccessUnitCount)
        val consumed = processed.position() - before
        if (consumed > 0) input.position(input.position() + consumed)

        if (processed.hasRemaining()) {
            pending = processed
            pendingTimeUs = presentationTimeUs
        } else {
            discardPending()
        }
        return handled
    }

    /** @return our processed buffer, or null when this buffer cannot be handled and must pass through. */
    private fun process(buffer: ByteBuffer, presentationTimeUs: Long): ByteBuffer? {
        val bytes = buffer.remaining()
        if (bytes <= 0 || frameBytes <= 0 || bytes % frameBytes != 0) return null

        val frameCount = bytes / frameBytes
        val sampleCount = frameCount * channelCount
        if (samples.size < sampleCount) samples = FloatArray(sampleCount)

        var out = output
        if (out == null || out.capacity() < bytes) {
            out = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
            output = out
        }

        PcmFrameCodec.read(buffer, buffer.position(), samples, sampleCount, encoding)
        engine.process(samples, frameCount, presentationTimeUs)
        // The limit has to be wide open before writing: the codec puts at absolute indices, and an
        // absolute put past the limit throws rather than growing it.
        out.clear()
        PcmFrameCodec.write(samples, sampleCount, out, encoding)
        out.limit(bytes)
        out.position(0)
        return out
    }

    override fun flush() {
        discardPending()
        // No position is offered here, and none is needed: the engine notices the jump on the next
        // buffer and re-anchors its orbit to wherever the listener actually landed.
        engine.reset(0L)
        super.flush()
    }

    override fun reset() {
        discardPending()
        engine.reset(0L)
        super.reset()
    }

    private fun discardPending() {
        pending = null
        pendingTimeUs = Long.MIN_VALUE
    }
}
