package fm.rizx.player.playback.spatial

import kotlin.math.floor

/**
 * A delay line that can be asked for a **fractional** number of samples back.
 *
 * This is what carries the interaural time difference, and the fractional part is the whole point: at
 * 48 kHz one sample is 21 µs, while the delay being modelled sweeps continuously between 0 and about
 * 650 µs as the sound orbits. Rounding to whole samples would step the delay roughly thirty times per
 * orbit, and every step is a discontinuity in the waveform — heard as a faint tick, and visible as a
 * spray of harmonics that were never played.
 *
 * Linear interpolation between the two neighbouring samples. It costs one multiply-add and rolls off
 * the very top octave slightly at fractional delays — inaudible on the far ear, which is being
 * low-passed by the head shadow anyway.
 *
 * The buffer is a power of two so the wrap is a mask rather than a modulo, and it is allocated once.
 */
internal class FractionalDelayLine(maxDelaySamples: Int) {

    private val capacity = nextPowerOfTwo(maxDelaySamples + 4)
    private val mask = capacity - 1
    private val buffer = FloatArray(capacity)
    private var writeIndex = 0

    val maxDelay: Float = maxDelaySamples.toFloat()

    fun write(x: Float) {
        buffer[writeIndex] = x
        writeIndex = (writeIndex + 1) and mask
    }

    /** @param delaySamples 0 returns the sample just written; values are clamped to the line's length. */
    fun read(delaySamples: Float): Float {
        val d = delaySamples.coerceIn(0f, maxDelay)
        val readPos = writeIndex - 1 - d
        val older = floor(readPos).toInt()
        val frac = readPos - older
        // Two's-complement `and` wraps negative indices correctly because capacity is a power of two.
        val a = buffer[older and mask]
        val b = buffer[(older + 1) and mask]
        return a + (b - a) * frac
    }

    fun reset() {
        buffer.fill(0f)
        writeIndex = 0
    }

    private companion object {
        fun nextPowerOfTwo(n: Int): Int {
            var v = 2
            while (v < n) v = v shl 1
            return v
        }
    }
}
