package fm.rizx.player.playback.spatial

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * The two filters the spatializer needs, and nothing else.
 *
 * Pure Kotlin, no allocation once constructed, and coefficients recomputed only when something actually
 * changes — a filter that recalculates `sin`/`cos` per sample would be the single most expensive thing
 * in the audio callback.
 */

/**
 * A second-order filter, transposed direct form II.
 *
 * **Used as a real high-pass, not as `input − lowPass(input)`.** The subtractive split is tempting
 * because it reconstructs the input exactly, but it does not *reject* anything: a low-pass shifts
 * phase as well as level, so at 60 Hz under a 150 Hz crossover the difference between the input and
 * its filtered self is still more than half the bass — which then gets panned around the listener's
 * head, exactly what the crossover exists to prevent. Nothing here ever reconstructs the signal (the
 * dry path is the untouched input), so there is no reason to pay for that property. Two of these in
 * series give 24 dB/octave, which actually keeps the low end at home.
 */
internal class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var s1 = 0f
    private var s2 = 0f

    fun setLowPass(cutoffHz: Float, sampleRateHz: Int, q: Float = Q_BUTTERWORTH) {
        // Above Nyquist the maths degenerates; keep a margin so a high crossover on a low sample rate
        // stays a filter rather than becoming noise.
        val f = cutoffHz.coerceIn(10f, sampleRateHz * 0.45f)
        val w0 = 2.0 * PI * f / sampleRateHz
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = (((1.0 - cosW0) / 2.0) / a0).toFloat()
        b1 = ((1.0 - cosW0) / a0).toFloat()
        b2 = b0
        a1 = ((-2.0 * cosW0) / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun setHighPass(cutoffHz: Float, sampleRateHz: Int, q: Float = Q_BUTTERWORTH) {
        val f = cutoffHz.coerceIn(10f, sampleRateHz * 0.45f)
        val w0 = 2.0 * PI * f / sampleRateHz
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = (((1.0 + cosW0) / 2.0) / a0).toFloat()
        b1 = ((-(1.0 + cosW0)) / a0).toFloat()
        b2 = b0
        a1 = ((-2.0 * cosW0) / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    /**
     * A bell centred on [centreHz]. Used for the front/back cue, where the gain is *signed*: the outer
     * ear resonates in this band for a sound in front of the listener and blocks it for one behind, so
     * the same filter swings positive and negative as the source travels round.
     */
    fun setPeaking(centreHz: Float, sampleRateHz: Int, gainDb: Float, q: Float) {
        val f = centreHz.coerceIn(10f, sampleRateHz * 0.45f)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / sampleRateHz
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha / a
        b0 = ((1.0 + alpha * a) / a0).toFloat()
        b1 = ((-2.0 * cosW0) / a0).toFloat()
        b2 = ((1.0 - alpha * a) / a0).toFloat()
        a1 = ((-2.0 * cosW0) / a0).toFloat()
        a2 = ((1.0 - alpha / a) / a0).toFloat()
    }

    fun process(x: Float): Float {
        val y = b0 * x + s1
        s1 = b1 * x - a1 * y + s2
        s2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        s1 = 0f
        s2 = 0f
    }

    private companion object {
        /** Maximally flat: the crossover should be inaudible, not resonant. */
        const val Q_BUTTERWORTH = 0.70710678f
    }
}

/**
 * A one-pole low-pass. Cheap, unconditionally stable, and gentle — which is what head shadow and
 * ambience damping both want: the far ear loses treble gradually, it does not fall off a cliff.
 */
internal class OnePole {
    private var a = 0f
    private var y = 0f

    fun setCutoff(cutoffHz: Float, sampleRateHz: Int) {
        val f = cutoffHz.coerceIn(20f, sampleRateHz * 0.49f)
        a = exp(-2.0 * PI * f / sampleRateHz).toFloat()
    }

    fun process(x: Float): Float {
        y = (1f - a) * x + a * y
        return y
    }

    fun reset() {
        y = 0f
    }
}
