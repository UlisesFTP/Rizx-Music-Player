package fm.rizx.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.cache.CacheManager
import fm.rizx.player.domain.playback.AudioEffectsController
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Settings screen: mirrors the persisted toggles and forwards changes to DataStore (and, for
 * normalize, to the live audio effect). Also measures/clears the image cache. The theme stays with
 * [fm.rizx.player.ui.player.PlayerViewModel] (it drives the whole app's theme), so it isn't duplicated here.
 */
@HiltViewModel
class PreferencesViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val audioEffects: AudioEffectsController,
    private val cache: CacheManager,
) : ViewModel() {

    val dataSaver: StateFlow<Boolean> = settings.dataSaver.asState(false)
    val crossfade: StateFlow<Boolean> = settings.crossfade.asState(false)
    val gapless: StateFlow<Boolean> = settings.gapless.asState(true)
    val normalize: StateFlow<Boolean> = settings.normalizeVolume.asState(false)

    private val _cacheSize = MutableStateFlow(cache.diskSizeLabel())
    val cacheSize: StateFlow<String> = _cacheSize.asStateFlow()

    fun setDataSaver(enabled: Boolean) { viewModelScope.launch { settings.setDataSaver(enabled) } }
    fun setCrossfade(enabled: Boolean) { viewModelScope.launch { settings.setCrossfade(enabled) } }
    fun setGapless(enabled: Boolean) { viewModelScope.launch { settings.setGapless(enabled) } }

    /** Persists **and** applies the live loudness effect (the controller writes the setting through). */
    fun setNormalize(enabled: Boolean) = audioEffects.setNormalizeVolume(enabled)

    fun clearCache() {
        viewModelScope.launch {
            cache.clear()
            _cacheSize.value = cache.diskSizeLabel()
        }
    }

    private fun <T> Flow<T>.asState(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
}
