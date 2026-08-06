package fm.rizx.player.data.recognition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Brings captured audio to the one format a signature may be computed from: mono, signed 16-bit
 * little-endian PCM at [SIGNATURE_SAMPLE_RATE_HZ].
 *
 * **Why this is band-limited rather than three lines of interpolation.** Dropping from 44.1 kHz to
 * 16 kHz throws away everything above 8 kHz, and anything still up there folds back down over the
 * music as alias tones — landing squarely inside the 250–5500 Hz range the fingerprint reads. Picking
 * nearest or linearly-interpolated samples does exactly that, and the damage is invisible: the
 * signature still encodes cleanly, it just describes peaks that were never played. So the input is
 * low-passed as part of the same convolution that resamples it, with a windowed-sinc kernel.
 *
 * Only used when the microphone could not be opened at 16 kHz — see `AndroidMicrophoneRecorder`, which
 * asks the platform for that rate first precisely so this stage can be skipped. Pure Kotlin, no
 * Android: it is fast enough (tens of milliseconds for a whole capture) that it needs no cancellation
 * checks of its own.
 */
internal class Pcm16Resampler {

    /**
     * @param pcm16LittleEndian interleaved signed 16-bit little-endian samples.
     * @param inputRateHz the rate [pcm16LittleEndian] was captured at.
     * @param channelCount interleaved channels; anything above one is averaged down to mono.
     * @return mono 16-bit little-endian PCM at [SIGNATURE_SAMPLE_RATE_HZ] — the input itself, not a
     *   copy, when it is already in that shape.
     */
    fun toMono16k(pcm16LittleEndian: ByteArray, inputRateHz: Int, channelCount: Int): ByteArray {
        require(pcm16LittleEndian.isNotEmpty()) { "nothing to resample" }
        require(pcm16LittleEndian.size % 2 == 0) {
            "PCM must hold whole 16-bit samples, got ${pcm16LittleEndian.size} bytes"
        }
        require(inputRateHz > 0) { "invalid sample rate $inputRateHz" }
        require(channelCount >= 1) { "invalid channel count $channelCount" }

        if (inputRateHz == SIGNATURE_SAMPLE_RATE_HZ && channelCount == 1) return pcm16LittleEndian

        val mono = downmix(pcm16LittleEndian, channelCount)
        val resampled = if (inputRateHz == SIGNATURE_SAMPLE_RATE_HZ) mono else resample(mono, inputRateHz)
        check(resampled.isNotEmpty()) { "resampling $inputRateHz Hz audio produced nothing" }

        val out = ByteBuffer.allocate(resampled.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in resampled) {
            out.putShort(sample.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        return out.array()
    }

    private fun downmix(bytes: ByteArray, channelCount: Int): DoubleArray {
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = shorts.remaining() / channelCount
        val mono = DoubleArray(frames)
        for (frame in 0 until frames) {
            var sum = 0.0
            for (channel in 0 until channelCount) sum += shorts.get(frame * channelCount + channel).toDouble()
            mono[frame] = sum / channelCount
        }
        return mono
    }

    /**
     * Windowed-sinc resampling. The kernel's cutoff follows the *lower* of the two rates, so the same
     * pass both anti-aliases on the way down and interpolates on the way up.
     */
    private fun resample(input: DoubleArray, inputRateHz: Int): DoubleArray {
        val ratio = SIGNATURE_SAMPLE_RATE_HZ.toDouble() / inputRateHz
        val cutoff = minOf(1.0, ratio) * CUTOFF_MARGIN
        // A narrower cutoff needs a proportionally longer kernel to keep the same transition width.
        val halfWidth = ceil(ZERO_CROSSINGS / cutoff).toInt()
        val outputCount = (input.size * ratio).toInt()
        if (outputCount <= 0) return DoubleArray(0)

        val output = DoubleArray(outputCount)
        for (n in 0 until outputCount) {
            val centre = n / ratio
            val first = ceil(centre - halfWidth).toInt()
            val last = (centre + halfWidth).toInt()
            var sum = 0.0
            for (i in first..last) {
                if (i < 0 || i >= input.size) continue
                sum += input[i] * kernel(centre - i, cutoff, halfWidth)
            }
            output[n] = sum * cutoff
        }
        return output
    }

    /** A sinc lobe under a Blackman window — the window is what stops the truncation from ringing. */
    private fun kernel(distance: Double, cutoff: Double, halfWidth: Int): Double {
        val magnitude = abs(distance)
        if (magnitude > halfWidth) return 0.0
        val x = PI * distance * cutoff
        val sinc = if (magnitude < 1e-9) 1.0 else sin(x) / x
        val t = PI * distance / halfWidth
        val window = 0.42 + 0.5 * cos(t) + 0.08 * cos(2 * t)
        return sinc * window
    }

    private companion object {
        /**
         * Keeps the cutoff just below Nyquist. Sitting exactly on it would need an infinitely sharp
         * filter, and the few hundred hertz given up here are far above the fingerprint's 5500 Hz top.
         */
        const val CUTOFF_MARGIN = 0.92

        /** Sinc lobes kept either side of each output sample: the stopband/CPU trade-off. */
        const val ZERO_CROSSINGS = 16.0
    }
}
