package fm.rizx.player.domain.playback

import fm.rizx.player.domain.model.SpatialAudioState
import kotlinx.coroutines.flow.StateFlow

/**
 * The only thing the UI is allowed to touch about spatial audio.
 *
 * Deliberately one switch and one flow. The DSP engine, the audio sink and the analyzer are all behind
 * this: a screen that could reach the engine would be one refactor away from doing per-frame work on the
 * main thread, and one bug away from changing a filter coefficient mid-buffer.
 *
 * There is no strength control. It existed, as three named levels, and it was the wrong shape for the
 * problem: two of the three were always the wrong answer for whoever picked them, and the real request
 * behind choosing one was simply "make this work". So the profiles are tuned to be worth switching on,
 * and the switch is the whole interface.
 *
 * [setEnabled] is immediate and takes effect on the song already playing — no restart, no rebuilt
 * player, no "applies to the next session". The transition is ramped inside the engine, so toggling
 * during playback is a fade, never a click.
 */
interface SpatialAudioController {
    val state: StateFlow<SpatialAudioState>

    fun setEnabled(enabled: Boolean)
}
