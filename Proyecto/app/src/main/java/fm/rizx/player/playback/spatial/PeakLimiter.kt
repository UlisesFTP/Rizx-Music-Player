package fm.rizx.player.playback.spatial

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Keeps the spatialized signal under the ceiling without audibly working.
 *
 * Adding a moved copy of the mix back on top of the original can push peaks past full scale, and the
 * naive answer — clamping each sample to ±1 — is hard clipping: it replaces the peak with a corner,
 * which is broadband distortion, and on a modern master (where peaks are constant) it would be
 * distorting continuously.
 *
 * So: a **stereo-linked** gain that follows the peak, with a fast attack and a slow release, plus a
 * soft knee as the last resort for anything that still overshoots between two gain updates. Linked
 * because moving the two channels by different amounts would swing the stereo image every time a
 * transient hit — the very thing this effect is supposed to be placing deliberately.
 */
internal class PeakLimiter(sampleRateHz: Int) {

    private val attack = coefficientFor(ATTACK_MS, sampleRateHz)
    private val release = coefficientFor(RELEASE_MS, sampleRateHz)
    private var gain = 1f

    /**
     * Applies gain reduction to one frame.
     *
     * @param frame the interleaved buffer, modified in place.
     * @param index offset of the left sample; right is `index + 1`.
     */
    fun process(frame: FloatArray, index: Int) {
        val peak = max(abs(frame[index]), abs(frame[index + 1]))
        val wanted = if (peak * gain > CEILING) CEILING / peak else 1f
        // Down fast so a transient is caught; back up slowly so the level does not pump between them.
        val coeff = if (wanted < gain) attack else release
        gain += (wanted - gain) * coeff
        frame[index] = softClip(frame[index] * gain)
        frame[index + 1] = softClip(frame[index + 1] * gain)
    }

    fun reset() {
        gain = 1f
    }

    /**
     * Bends the last fraction of a decibel instead of cutting it. Continuous in value and slope at the
     * ceiling, and asymptotic to full scale, so it can never produce a sample outside ±1.
     */
    private fun softClip(x: Float): Float {
        val magnitude = abs(x)
        if (magnitude <= CEILING) return x
        val over = magnitude - CEILING
        val room = 1f - CEILING
        val bent = CEILING + room * (1f - exp(-over / room))
        return if (x >= 0f) bent else -bent
    }

    private companion object {
        /** −1 dBFS. Leaves room for the inter-sample peaks a consumer DAC will reconstruct. */
        const val CEILING = 0.891f

        const val ATTACK_MS = 3f
        const val RELEASE_MS = 180f

        fun coefficientFor(ms: Float, sampleRateHz: Int): Float =
            1f - exp(-1.0 / (ms / 1000.0 * sampleRateHz)).toFloat()
    }
}
