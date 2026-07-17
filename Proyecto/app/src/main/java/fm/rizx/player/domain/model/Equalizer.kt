package fm.rizx.player.domain.model

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
) {
    companion object {
        val Unavailable = EqualizerState()
    }
}

/** Built-in presets, mapped to per-band gains generically so they work for any device band count. */
enum class EqPreset { FLAT, BASS, TREBLE, VOCAL, LOUDNESS }

object EqPresets {

    /**
     * Pure preset → per-band millibel levels for [bandCount] bands, scaled to [maxMillibel]. Bands are
     * ordered low→high frequency; positions are normalized to 0..1 so a preset shapes the same curve
     * regardless of how many bands the device exposes.
     */
    fun levels(preset: EqPreset, bandCount: Int, maxMillibel: Int): List<Int> {
        if (bandCount <= 0) return emptyList()
        val boost = (maxMillibel * 0.6f).toInt()
        val mild = (maxMillibel * 0.3f).toInt()
        return (0 until bandCount).map { i ->
            val pos = if (bandCount == 1) 0.5f else i.toFloat() / (bandCount - 1)
            when (preset) {
                EqPreset.FLAT -> 0
                EqPreset.BASS -> if (pos <= 0.35f) boost else if (pos <= 0.5f) mild else 0
                EqPreset.TREBLE -> if (pos >= 0.65f) boost else if (pos >= 0.5f) mild else 0
                EqPreset.VOCAL -> if (pos in 0.35f..0.65f) boost else 0
                EqPreset.LOUDNESS -> if (pos <= 0.25f || pos >= 0.75f) mild else 0
            }
        }
    }
}
