package fm.rizx.player.domain.model

import kotlin.math.log10
import kotlin.math.roundToInt

/** One equalizer band: its device index, center frequency (Hz), and current gain (millibels). */
data class EqBand(val index: Int, val centerFreqHz: Int, val levelMillibel: Int)

/**
 * UI-facing equalizer snapshot (Phase 15). [available] is false until the effect is attached to an
 * active audio session (i.e. something is playing). Band count/frequencies/range come from the device.
 */
data class EqualizerState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val minLevelMillibel: Int = 0,
    val maxLevelMillibel: Int = 0,
    val bands: List<EqBand> = emptyList(),
    /**
     * The automatic equalizer owns the effect: the bands below are what *it* chose for the song playing
     * now, and the user's manual curve is put aside until this turns false again. The screen shows them
     * read-only, because a slider that is overwritten on the next track is a control that lies.
     */
    val auto: Boolean = false,
    /** The catalogue's own genre wording for the current song ("Música Mexicana"), when it found one. */
    val autoLabel: String? = null,
    /**
     * The preset whose curve the bands currently equal, or null for a hand-made curve. Derived, not
     * remembered: it is recomputed from the levels on every change, so it is right after a restart and
     * goes back to null the moment one band is dragged off the preset.
     */
    val preset: EqPreset? = null,
) {
    companion object {
        val Unavailable = EqualizerState()
    }
}

/** Broad listening profiles. They shape timbre; they are not headphone or mastering corrections. */
enum class EqPreset {
    FLAT,
    BASS,
    TREBLE,
    VOCAL,
    LOUDNESS,
    POP,
    ROCK,
    HIP_HOP,
    ELECTRONIC,
    LATIN,
    RNB,
    JAZZ,
    CLASSICAL,
    ACOUSTIC,
    METAL,
    PODCAST,
    NIGHT,
}

object EqPresets {

    /**
     * Pure preset → levels for the ranges this device actually exposes. Curves live at octave anchors,
     * are interpolated in log-frequency and averaged across each band; wide five-band implementations
     * therefore receive the same intent as narrower ten-band implementations.
     */
    fun levels(
        preset: EqPreset,
        bands: List<EqBandRange>,
        minMillibel: Int,
        maxMillibel: Int,
    ): List<Int> {
        if (bands.isEmpty()) return emptyList()
        val anchors = CURVES.getValue(preset)
        val raw = bands.map { band ->
            val low = band.lowHz.coerceAtLeast(MIN_HZ)
            val high = band.highHz.coerceAtMost(MAX_HZ)
            if (high > low) averageOver(anchors, low, high) else valueAt(anchors, band.centerHz.toFloat())
        }
        val mean = raw.sum() / raw.size
        val centered = raw.map { it - mean }
        val excess = (centered.maxOrNull() ?: 0f) - MAX_BOOST_DB
        val safe = if (excess > 0f) centered.map { it - excess } else centered
        return safe.map { (it * 100f).roundToInt().coerceIn(minMillibel, maxMillibel) }
    }

    /**
     * The preset that produces exactly [levels] on this device, or null. The comparison is exact on the
     * rounded millibels because that is what [levels] wrote and what the effect reads back.
     */
    fun matching(
        levels: List<Int>,
        bands: List<EqBandRange>,
        minMillibel: Int,
        maxMillibel: Int,
    ): EqPreset? {
        if (levels.isEmpty() || bands.size != levels.size) return null
        return EqPreset.entries.firstOrNull { levels(it, bands, minMillibel, maxMillibel) == levels }
    }

    /** Compatibility helper for callers that only know a count; real playback uses device ranges above. */
    fun levels(preset: EqPreset, bandCount: Int, maxMillibel: Int): List<Int> {
        if (bandCount <= 0) return emptyList()
        val bands = List(bandCount) { index ->
            val t = if (bandCount == 1) 0.5 else index.toDouble() / (bandCount - 1)
            val hz = Math.pow(10.0, log10(ANCHORS_HZ.first().toDouble()) +
                (log10(ANCHORS_HZ.last().toDouble()) - log10(ANCHORS_HZ.first().toDouble())) * t).roundToInt()
            EqBandRange(index, hz, hz, hz)
        }
        return levels(preset, bands, minMillibel = -maxMillibel, maxMillibel = maxMillibel)
    }

    private fun valueAt(curve: FloatArray, hz: Float): Float {
        val frequency = hz.coerceIn(MIN_HZ.toFloat(), MAX_HZ.toFloat())
        if (frequency <= ANCHORS_HZ.first()) return curve.first()
        if (frequency >= ANCHORS_HZ.last()) return curve.last()
        val upper = ANCHORS_HZ.indexOfFirst { it >= frequency }
        val lower = upper - 1
        val logLower = log10(ANCHORS_HZ[lower].toDouble())
        val span = log10(ANCHORS_HZ[upper].toDouble()) - logLower
        val t = ((log10(frequency.toDouble()) - logLower) / span).toFloat()
        return curve[lower] + (curve[upper] - curve[lower]) * t
    }

    private fun averageOver(curve: FloatArray, lowHz: Int, highHz: Int): Float {
        val logLow = log10(lowHz.toDouble())
        val logHigh = log10(highHz.toDouble())
        return (0 until BAND_SAMPLES).sumOf { index ->
            val t = (index + 0.5) / BAND_SAMPLES
            valueAt(curve, Math.pow(10.0, logLow + (logHigh - logLow) * t).toFloat()).toDouble()
        }.toFloat() / BAND_SAMPLES
    }

    private val ANCHORS_HZ = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

    /* Curves in dB. Broad shapes are intentional: Android commonly exposes only five wide bands. */
    private val CURVES: Map<EqPreset, FloatArray> = mapOf(
        //                                  31    62   125   250   500    1k    2k    4k    8k   16k
        EqPreset.FLAT to floatArrayOf(       0f,   0f,   0f,   0f,   0f,   0f,   0f,   0f,   0f,   0f),
        EqPreset.BASS to floatArrayOf(       4f, 4.5f,   4f,   2f,   0f,  -1f,  -1f, -.5f,   0f,   0f),
        EqPreset.TREBLE to floatArrayOf(   -.5f, -.5f, -.5f,   0f,   0f,  .5f, 1.5f,   3f,   4f, 4.5f),
        EqPreset.VOCAL to floatArrayOf(     -4f,  -3f,  -2f,  -1f,   1f,   3f,   4f, 3.5f,   1f,  -1f),
        EqPreset.LOUDNESS to floatArrayOf( 3.5f,   4f,   3f,   1f,  -1f,  -2f,  -1f,   1f,   3f, 3.5f),
        EqPreset.POP to floatArrayOf(      1.5f,   3f, 1.5f,-1.5f,  -1f,   0f,   1f,   2f, 2.5f,   2f),
        EqPreset.ROCK to floatArrayOf(      .5f, 2.5f,   1f,  -2f,  -1f,  .5f,   2f, 2.5f, 1.5f,   1f),
        EqPreset.HIP_HOP to floatArrayOf(  3.5f, 4.5f,   2f,-2.5f,-1.5f,   0f,   1f,   2f,   2f, 1.5f),
        EqPreset.ELECTRONIC to floatArrayOf( 4f, 4.5f, 1.5f,  -2f,  -2f,  -1f,  .5f,   2f,   3f, 3.5f),
        EqPreset.LATIN to floatArrayOf(      1f, 2.5f,   2f,  -1f,-1.5f, -.5f, 1.5f,   2f, 1.5f,   1f),
        EqPreset.RNB to floatArrayOf(        2f,   3f,   2f, -.5f, -.5f,  .5f,   1f, 1.5f, 1.5f, 1.5f),
        EqPreset.JAZZ to floatArrayOf(       1f, 1.5f,   1f,   0f,   0f,  .5f,  .5f,   1f, 1.5f, 1.5f),
        EqPreset.CLASSICAL to floatArrayOf(  1f,   1f,  .5f,   0f,   0f,   0f,   0f,  .5f,   1f, 1.5f),
        EqPreset.ACOUSTIC to floatArrayOf(   0f,   1f, 1.5f,  -1f, -.5f,  .5f,   1f, 1.5f, 1.5f, 1.5f),
        EqPreset.METAL to floatArrayOf(      0f,   2f,  .5f,-2.5f,-2.5f,  -1f, 1.5f,   3f,   2f,   1f),
        EqPreset.PODCAST to floatArrayOf(   -4f,  -3f,  -2f, -.5f, 1.5f,   3f,   4f,   3f,   0f,  -2f),
        EqPreset.NIGHT to floatArrayOf(     -4f,-3.5f,  -2f,   0f,   1f, 1.5f,  .5f,  -1f,  -2f,  -3f),
    )

    private const val MIN_HZ = 20
    private const val MAX_HZ = 20_000
    private const val MAX_BOOST_DB = 4f
    private const val BAND_SAMPLES = 7
}
