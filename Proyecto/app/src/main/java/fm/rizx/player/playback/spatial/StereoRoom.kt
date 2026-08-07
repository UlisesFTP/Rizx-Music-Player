package fm.rizx.player.playback.spatial

import kotlin.math.pow

/**
 * The space the music is playing in — a large hall or an arena rather than a booth.
 *
 * **Why a small room did not work.** The first version used four taps between 23 and 44 ms with a
 * 0.6 s tail, which is a rehearsal room. Played back it read as a faint sheen rather than as a place:
 * the ear judges the *size* of a space almost entirely from how long it takes the first reflection to
 * arrive and how long the tail runs, and neither of those said "big". So:
 *
 * - a **pre-delay** before anything else. The gap between the direct sound and the first reflection is
 *   the single strongest size cue there is — 40 ms of silence is a hall, 5 ms is a cupboard;
 * - **six long taps**, 53–127 ms, mutually indivisible so their echoes never line up into a pitched
 *   metallic ring;
 * - **two allpass diffusers per ear** afterwards. Combs alone are a handful of distinct echoes; the
 *   allpasses smear them into something continuous, which is the difference between hearing repeats
 *   and hearing a room;
 * - **more damping**. Air and a crowd absorb treble over a long path, and a bright tail sounds like a
 *   spring reverb rather than a stadium.
 *
 * Still no convolution, no impulse responses, no new dependency — and still deterministic, so the same
 * audio renders identically here and in an exported file.
 */
internal class StereoRoom(private val sampleRateHz: Int) {

    private val preDelay = FractionalDelayLine(msToSamples(PRE_DELAY_MS) + 2)
    private val preDelaySamples = msToSamples(PRE_DELAY_MS).toFloat()

    private val delaysSamples = TAP_MS.map { msToSamples(it).coerceAtLeast(1) }
    private val lines = delaysSamples.map { FractionalDelayLine(it + 2) }
    private val damping = List(TAP_MS.size) { OnePole().apply { setCutoff(DAMPING_HZ, sampleRateHz) } }
    private val feedback = FloatArray(TAP_MS.size)

    private val diffusers = List(4) { Allpass(msToSamples(ALLPASS_MS[it % ALLPASS_MS.size])) }

    /**
     * A **real** high-pass on the send, for the same reason the crossover is one.
     *
     * This was `send = x − lowPass(x)` and it did not do its job: a one-pole shifts phase as well as
     * level, so at 60 Hz under a 220 Hz cutoff the difference still carried a quarter of the bass into
     * the tail. The tail is the most decorrelated thing in the chain, so that quarter came straight back
     * out as the two channels disagreeing about where the low end was.
     */
    private val lowCut = Biquad().apply { setHighPass(SEND_HIGH_PASS_HZ, sampleRateHz) }

    init {
        setDecay(1.6f, sampleRateHz)
    }

    /** Feedback per tap for a given RT60. Longer taps need more feedback to decay over the same time. */
    fun setDecay(decaySec: Float, sampleRateHz: Int) {
        val decay = decaySec.coerceAtLeast(0.05f)
        for (i in feedback.indices) {
            val tapSec = delaysSamples[i] / sampleRateHz.toFloat()
            // −60 dB after decaySec: g = 10^(−3 · tap / decay), the standard Schroeder relation.
            feedback[i] = 10f.pow(-3f * tapSec / decay).coerceIn(0f, MAX_FEEDBACK)
        }
    }

    /**
     * Feeds one mono sample in and returns the stereo tail.
     *
     * @param out two floats, left then right. Reused by the caller — nothing is allocated here.
     */
    fun process(x: Float, out: FloatArray) {
        // **The low end is not sent into the room.** A hall barely reverberates at 60 Hz, and a tail
        // built from bass is decorrelated between the ears — which reads as loose, smeared low end and
        // costs the track its weight. Engineers high-pass a reverb send for exactly this reason.
        val send = lowCut.process(x)

        preDelay.write(send)
        val delayed = preDelay.read(preDelaySamples)

        var left = 0f
        var right = 0f
        for (i in lines.indices) {
            val tail = lines[i].read(delaysSamples[i].toFloat())
            lines[i].write(delayed + damping[i].process(tail) * feedback[i])
            if (i and 1 == 0) left += tail else right += tail
        }

        left = diffusers[1].process(diffusers[0].process(left * TAP_GAIN))
        right = diffusers[3].process(diffusers[2].process(right * TAP_GAIN))
        out[0] = left
        out[1] = right
    }

    fun reset() {
        preDelay.reset()
        lines.forEach { it.reset() }
        damping.forEach { it.reset() }
        diffusers.forEach { it.reset() }
        lowCut.reset()
    }

    private fun msToSamples(ms: Float): Int = (ms * sampleRateHz / 1000f).toInt()

    /** A Schroeder allpass: passes every frequency at the same level but scrambles their timing. */
    private class Allpass(delaySamples: Int) {
        private val line = FractionalDelayLine(delaySamples + 2)
        private val delay = delaySamples.coerceAtLeast(1).toFloat()

        fun process(x: Float): Float {
            val delayed = line.read(delay)
            val y = -GAIN * x + delayed
            line.write(x + GAIN * y)
            return y
        }

        fun reset() = line.reset()

        private companion object {
            const val GAIN = 0.5f
        }
    }

    private companion object {
        /**
         * The distance to the first wall. This is what the ear measures a room with, and 42 ms puts it
         * a long way away.
         */
        const val PRE_DELAY_MS = 42f

        /** Milliseconds. Deliberately awkward numbers so the taps never coincide. */
        val TAP_MS = listOf(53.7f, 67.1f, 79.3f, 97.7f, 113.9f, 127.3f)

        /** Diffusion stages, short and prime-ish. */
        val ALLPASS_MS = listOf(5.3f, 1.7f)

        /** Three taps sum into each ear; this keeps the tail at unity-ish rather than tripling it. */
        const val TAP_GAIN = 0.34f

        /** A big space full of people eats treble on every bounce. */
        const val DAMPING_HZ = 3_200f

        /** Below this the room is not fed at all, so the low end stays tight and centred. */
        const val SEND_HIGH_PASS_HZ = 220f

        /** A safety rail: feedback at or above 1.0 is an oscillator, not a room. */
        const val MAX_FEEDBACK = 0.88f
    }
}
