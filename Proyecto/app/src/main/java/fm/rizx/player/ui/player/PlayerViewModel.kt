
package fm.rizx.player.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.ui.model.SampleData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-wide playback + theme state shared across every screen and the mini-player.
 * This is the UI-shell stand-in for the eventual PlaybackController / MediaSession
 * (ROADMAP Phase 6); for now the "transport" is a 1 Hz progress ticker over a fixed
 * track duration, matching the interactive prototype.
 */
data class PlayerUiState(
    val isDark: Boolean = true,
    val isPlaying: Boolean = true,
    val progress: Float = 0.42f,
    val likedNp: Boolean = true,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    val durationSec: Int = SampleData.TRACK_DURATION_SEC

    init {
        // The theme now persists (DataStore): mirror the stored value into state, and toggling writes
        // it back so the choice survives restart.
        viewModelScope.launch {
            settings.darkTheme.collect { dark -> _state.update { it.copy(isDark = dark) } }
        }

        // Advance progress ~1 Hz, but only while the UI is actually observing `state`. Gating on
        // the subscriber count keeps the ticker deterministically testable (no unbounded loop in
        // construction) and avoids doing work when nothing is collecting (e.g. app backgrounded).
        viewModelScope.launch {
            _state.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed ->
                    if (!observed) return@collectLatest
                    while (true) {
                        delay(1000)
                        _state.update { s ->
                            if (!s.isPlaying) return@update s
                            var p = s.progress + 1f / durationSec
                            if (p >= 1f) p = 0f
                            s.copy(progress = p)
                        }
                    }
                }
        }
    }

    fun togglePlay() = _state.update { it.copy(isPlaying = !it.isPlaying) }

    fun toggleTheme() {
        viewModelScope.launch { settings.setDarkTheme(!_state.value.isDark) }
    }

    fun toggleLikeNp() = _state.update { it.copy(likedNp = !it.likedNp) }

    /** Scrub to an absolute position (0..1). */
    fun seekTo(fraction: Float) = _state.update { it.copy(progress = fraction.coerceIn(0f, 1f)) }
}
