package fm.rizx.player.domain.playback

import fm.rizx.player.domain.model.EqPreset
import fm.rizx.player.domain.model.EqualizerState
import kotlinx.coroutines.flow.StateFlow

/**
 * User-facing control surface for audio effects (equalizer), observed by the UI. The concrete
 * implementation owns the platform effect bound to the playback session; the service attaches/detaches
 * it. When no session is active, [state] reports `available = false` and mutations are no-ops.
 */
interface AudioEffectsController {
    val state: StateFlow<EqualizerState>
    fun setEqualizerEnabled(enabled: Boolean)
    fun setBandLevel(index: Int, millibel: Int)
    fun applyPreset(preset: EqPreset)

    /**
     * Normalize volume: a fixed [android.media.audiofx.LoudnessEnhancer] gain that lifts quiet tracks
     * (an approximation — not per-track ReplayGain). Persisted and re-applied on the next session.
     */
    fun setNormalizeVolume(enabled: Boolean)

    /**
     * The automatic equalizer: a curve per song, from its genre and its own measured spectrum.
     *
     * Only persists the preference. The transition itself — taking the effect over, and handing the
     * user's manual curve back when it is switched off — belongs to the component that watches what is
     * playing (`playback/AutoEqualizer`), so that one place owns it.
     */
    fun setAutoEqualizer(enabled: Boolean)
}
