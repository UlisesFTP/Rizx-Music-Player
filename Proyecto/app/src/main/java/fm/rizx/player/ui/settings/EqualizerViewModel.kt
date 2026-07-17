package fm.rizx.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.domain.model.EqPreset
import fm.rizx.player.domain.model.EqualizerState
import fm.rizx.player.domain.playback.AudioEffectsController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Backs the Equalizer screen — observes the effect state and forwards user changes to it. */
@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val effects: AudioEffectsController,
) : ViewModel() {

    val state: StateFlow<EqualizerState> =
        effects.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EqualizerState.Unavailable)

    fun setEnabled(enabled: Boolean) = effects.setEqualizerEnabled(enabled)
    fun setBand(index: Int, millibel: Int) = effects.setBandLevel(index, millibel)
    fun applyPreset(preset: EqPreset) = effects.applyPreset(preset)
}
