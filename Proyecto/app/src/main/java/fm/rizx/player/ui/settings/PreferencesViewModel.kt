package fm.rizx.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.cache.CacheManager
import fm.rizx.player.core.region.RegionResolver
import fm.rizx.player.data.local.settings.SettingsRepositoryImpl
import fm.rizx.player.data.local.settings.SettingsRepositoryImpl.Companion.DEFAULT_AUDIO_CACHE_BYTES
import fm.rizx.player.domain.model.LyricsVisualQuality
import fm.rizx.player.domain.model.RadioMode
import fm.rizx.player.domain.playback.AudioEffectsController
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.playback.AudioOutputCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val audioOutput: AudioOutputCapabilities,
    private val region: RegionResolver,
) : ViewModel() {

    /** Regional-recommendations consent: null = never asked, true = on, false = declined. */
    val regionalRecs: StateFlow<Boolean?> = settings.recsRegionalConsent.asState(null)

    /** Which engine keeps "next" going after a song played from the feed or from search. */
    val radioAlgorithm: StateFlow<RadioMode> =
        settings.radioAlgorithm.asState(SettingsRepositoryImpl.DEFAULT_RADIO_ALGORITHM)

    fun setRadioAlgorithm(mode: RadioMode) { viewModelScope.launch { settings.setRadioAlgorithm(mode) } }

    /** How much the karaoke lyrics renderer is allowed to spend. */
    val lyricsQuality: StateFlow<LyricsVisualQuality> =
        settings.lyricsVisualQuality.asState(LyricsVisualQuality.AUTOMATIC)

    fun setLyricsQuality(quality: LyricsVisualQuality) {
        viewModelScope.launch { settings.setLyricsVisualQuality(quality) }
    }

    /** The detected country's display name (SIM/locale — no permission), for the row caption. */
    val regionCountry: String? get() = runCatching { region.countryDisplayName() }.getOrNull()

    val dataSaver: StateFlow<Boolean> = settings.dataSaver.asState(false)
    val crossfade: StateFlow<Boolean> = settings.crossfade.asState(false)
    val gapless: StateFlow<Boolean> = settings.gapless.asState(true)
    val normalize: StateFlow<Boolean> = settings.normalizeVolume.asState(false)
    val hiRes: StateFlow<Boolean> = settings.hiResOutput.asState(false)

    private val _cacheSize = MutableStateFlow(cache.diskSizeLabel())
    val cacheSize: StateFlow<String> = _cacheSize.asStateFlow()

    /** What the device's DAC can do (native/max sample rate + float), shown beside the Hi-Res toggle. */
    private val _audioOutputLabel = MutableStateFlow(runCatching { audioOutput.describe() }.getOrDefault(""))
    val audioOutputLabel: StateFlow<String> = _audioOutputLabel.asStateFlow()

    /** The offline-cache ceiling, as a label ("512 MB"). */
    val audioCacheLimitLabel: StateFlow<String> =
        settings.audioCacheBytes.map(::cacheLimitLabel).asState(cacheLimitLabel(DEFAULT_AUDIO_CACHE_BYTES))

    /** The same consent the For-you card asks for — this row can grant or revoke it any time. */
    fun setRegionalRecs(enabled: Boolean) { viewModelScope.launch { settings.setRecsRegionalConsent(enabled) } }

    fun setDataSaver(enabled: Boolean) { viewModelScope.launch { settings.setDataSaver(enabled) } }
    fun setCrossfade(enabled: Boolean) { viewModelScope.launch { settings.setCrossfade(enabled) } }
    fun setGapless(enabled: Boolean) { viewModelScope.launch { settings.setGapless(enabled) } }

    /** Persisted output preference; the audio sink reads it at the next playback-service start. */
    fun setHiRes(enabled: Boolean) { viewModelScope.launch { settings.setHiResOutput(enabled) } }

    /** Persists **and** applies the live loudness effect (the controller writes the setting through). */
    fun setNormalize(enabled: Boolean) = audioEffects.setNormalizeVolume(enabled)

    fun clearCache() {
        viewModelScope.launch {
            cache.clear()
            _cacheSize.value = cache.diskSizeLabel()
        }
    }

    /**
     * Steps to the next cache ceiling, wrapping around. A cycling row beats a dialog for four values,
     * and the size only takes effect at the next playback session anyway — the cache takes its limit
     * when it opens.
     */
    fun cycleAudioCacheLimit() {
        viewModelScope.launch {
            val current = settings.audioCacheBytes.first()
            val next = CACHE_LIMITS.firstOrNull { it > current } ?: CACHE_LIMITS.first()
            settings.setAudioCacheBytes(next)
        }
    }

    private fun <T> Flow<T>.asState(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    private companion object {
        val CACHE_LIMITS = listOf(
            256L * 1024 * 1024,
            512L * 1024 * 1024,
            1024L * 1024 * 1024,
            2048L * 1024 * 1024,
        )

        fun cacheLimitLabel(bytes: Long): String {
            val mb = bytes / (1024 * 1024)
            return if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
        }
    }
}
