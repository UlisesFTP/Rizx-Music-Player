package fm.rizx.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.cache.CacheManager
import fm.rizx.player.core.network.DataSaverState
import fm.rizx.player.core.region.RegionResolver
import fm.rizx.player.data.local.settings.SettingsRepositoryImpl
import fm.rizx.player.data.local.settings.SettingsRepositoryImpl.Companion.DEFAULT_AUDIO_CACHE_BYTES
import fm.rizx.player.domain.lossless.LosslessIndexSource
import fm.rizx.player.domain.model.AudioQualityMode
import fm.rizx.player.domain.model.DownloadFormat
import fm.rizx.player.domain.model.SpatialAudioMode
import fm.rizx.player.domain.model.CanvasDiagnostics
import fm.rizx.player.domain.model.CanvasNetworkPolicy
import fm.rizx.player.domain.model.CanvasQuality
import fm.rizx.player.domain.model.LyricsVisualQuality
import fm.rizx.player.domain.model.RadioMode
import fm.rizx.player.domain.playback.AudioEffectsController
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.repository.CanvasRepository
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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@HiltViewModel
class PreferencesViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val audioEffects: AudioEffectsController,
    private val cache: CacheManager,
    private val audioOutput: AudioOutputCapabilities,
    private val region: RegionResolver,
    private val registry: ProviderRegistry,
    private val canvas: CanvasRepository,
    private val losslessIndex: LosslessIndexSource,
    private val dataSaverState: DataSaverState,
    private val spatial: fm.rizx.player.domain.playback.SpatialAudioController,
) : ViewModel() {

    /** A selectable Home-feed source: a registered dashboard provider. */
    data class FeedSource(val id: String, val name: String)

    /** The animated-cover switches. Off / unmetered-only / not in power-save are the defaults. */
    val canvasEnabled: StateFlow<Boolean> = settings.canvasEnabled.asState(false)
    val canvasNetwork: StateFlow<CanvasNetworkPolicy> =
        settings.canvasNetworkPolicy.asState(CanvasNetworkPolicy.UNMETERED_ONLY)
    val canvasOnBatterySaver: StateFlow<Boolean> = settings.canvasOnBatterySaver.asState(false)
    val canvasQuality: StateFlow<CanvasQuality> = settings.canvasQuality.asState(CanvasQuality.AUTO)
    val canvasApple: StateFlow<Boolean> = settings.canvasAppleEnabled.asState(true)
    val canvasYoutube: StateFlow<Boolean> = settings.canvasYoutubeEnabled.asState(true)

    /** What the last lookup did. Read from the repository, which outlives the Now Playing screen. */
    val canvasDiagnostics: StateFlow<CanvasDiagnostics> = canvas.lastDiagnostics

    fun setCanvasEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setCanvasEnabled(enabled) }
    }

    fun setCanvasNetwork(policy: CanvasNetworkPolicy) {
        viewModelScope.launch { settings.setCanvasNetworkPolicy(policy) }
    }

    fun setCanvasOnBatterySaver(allowed: Boolean) {
        viewModelScope.launch { settings.setCanvasOnBatterySaver(allowed) }
    }

    fun setCanvasQuality(quality: CanvasQuality) {
        viewModelScope.launch { settings.setCanvasQuality(quality) }
    }

    fun setCanvasApple(enabled: Boolean) {
        viewModelScope.launch { settings.setCanvasAppleEnabled(enabled) }
    }

    fun setCanvasYoutube(enabled: Boolean) {
        viewModelScope.launch { settings.setCanvasYoutubeEnabled(enabled) }
    }

    /** Regional-recommendations consent: null = never asked, true = on, false = declined. */
    val regionalRecs: StateFlow<Boolean?> = settings.recsRegionalConsent.asState(null)

    /** Which engine keeps "next" going after a song played from the feed or from search. */
    val radioAlgorithm: StateFlow<RadioMode> =
        settings.radioAlgorithm.asState(SettingsRepositoryImpl.DEFAULT_RADIO_ALGORITHM)

    fun setRadioAlgorithm(mode: RadioMode) { viewModelScope.launch { settings.setRadioAlgorithm(mode) } }

    /** Which source fills Home: one dashboard provider's id, or `FEED_PROVIDER_ALL`. */
    val feedProvider: StateFlow<String> =
        settings.feedProvider.asState(SettingsRepositoryImpl.DEFAULT_FEED_PROVIDER)

    /**
     * The pickable feed sources, read from the registry rather than hard-coded — a dashboard plugin
     * (ListenBrainz, Bandcamp…) shows up here the moment it registers, with no change to this screen.
     */
    private val _feedSources = MutableStateFlow(readFeedSources())
    val feedSources: StateFlow<List<FeedSource>> = _feedSources.asStateFlow()

    /**
     * Re-reads the registry. The picker calls this as it opens because installing a plugin happens on
     * the Plugins screen, which this one navigates *to* — so this ViewModel survives the round trip
     * and would otherwise still be listing the sources from before the install.
     */
    fun refreshFeedSources() { _feedSources.value = readFeedSources() }

    private fun readFeedSources(): List<FeedSource> =
        runCatching { registry.list(ProviderKind.DASHBOARD).map { FeedSource(it.id, it.name) } }
            .getOrDefault(emptyList())

    fun setFeedProvider(id: String) { viewModelScope.launch { settings.setFeedProvider(id) } }

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

    /** The automatic equalizer (a curve per song, from its genre + its own spectrum). Off by default. */
    val autoEq: StateFlow<Boolean> = settings.autoEqualizer.asState(false)

    /** Adaptive stereo spatialization. Off by default; applies live to whatever is playing. */
    val spatialAudioOn: StateFlow<Boolean> =
        settings.spatialAudioMode.map { it != SpatialAudioMode.OFF }.asState(false)
    val avoidDoubleSpatialization: StateFlow<Boolean> =
        settings.avoidDoubleSpatialization.asState(true)

    /**
     * Standard / best compressed / prefer lossless — **as stored**, not as in force.
     *
     * The dialog ticks this one, because it is the user's choice and data saving must not appear to have
     * changed it. What is actually happening right now is [savingActive], which the row's caption says.
     */
    val audioQuality: StateFlow<AudioQualityMode> =
        settings.audioQualityMode.asState(AudioQualityMode.STANDARD)

    /**
     * Whether data saving is in force, from Rizx's switch or Android's.
     *
     * Drives the "forced by Data saver" captions. Kept separate from the stored settings so the screen
     * can show both truths at once: what you chose, and what is happening instead.
     */
    val savingActive: StateFlow<Boolean> = dataSaverState.saving.asState(false)

    /**
     * Whether any installed plugin publishes a FLAC index — the thing that makes "Prefer Lossless" a
     * real option rather than a switch that silently never does anything.
     *
     * Polled rather than observed because the provider registry has no change stream; the Settings row
     * refreshes it as the dialog opens, which is the moment after the user could have installed one.
     */
    private val _losslessAvailable = MutableStateFlow(runCatching { losslessIndex.isAvailable() }.getOrDefault(false))
    val losslessAvailable: StateFlow<Boolean> = _losslessAvailable.asStateFlow()

    fun refreshLosslessAvailability() {
        _losslessAvailable.value = runCatching { losslessIndex.isAvailable() }.getOrDefault(false)
    }

    /** Only look for a FLAC on an unmetered link. On by default — these files run 25-27 MB. */
    val losslessWifiOnly: StateFlow<Boolean> = settings.losslessWifiOnly.asState(true)
    val downloadFormat: StateFlow<DownloadFormat> = settings.downloadFormat.asState(DownloadFormat.ORIGINAL)

    /** Null = the user has never been asked; the row reads that as off, which is what it means. */
    val saveToPhone: StateFlow<Boolean?> = settings.saveDownloadsToPhone.asState(null)

    val showTechnicalFormat: StateFlow<Boolean> = settings.showTechnicalFormat.asState(true)

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
    fun setAudioQuality(mode: AudioQualityMode) {
        viewModelScope.launch { settings.setAudioQualityMode(mode) }
    }

    fun setLosslessWifiOnly(enabled: Boolean) {
        viewModelScope.launch { settings.setLosslessWifiOnly(enabled) }
    }

    fun setDownloadFormat(format: DownloadFormat) {
        viewModelScope.launch { settings.setDownloadFormat(format) }
    }

    fun setSaveToPhone(enabled: Boolean) {
        viewModelScope.launch { settings.setSaveDownloadsToPhone(enabled) }
    }

    fun setShowTechnicalFormat(enabled: Boolean) {
        viewModelScope.launch { settings.setShowTechnicalFormat(enabled) }
    }

    /** Persists **and** applies the live loudness effect (the controller writes the setting through). */
    fun setNormalize(enabled: Boolean) = audioEffects.setNormalizeVolume(enabled)

    /**
     * Persists the automatic equalizer. Goes through the controller rather than straight to DataStore for
     * the same reason normalize does: the audio side is what owns the transition, and routing both through
     * one door keeps "who took the effect over" answerable in one place.
     */
    fun setAutoEq(enabled: Boolean) = audioEffects.setAutoEqualizer(enabled)

    // Through the controller rather than straight to the settings repository, so that turning it on
    // here and turning it on from the player's menu are the same code path and cannot drift apart.
    fun setSpatialAudio(enabled: Boolean) = spatial.setEnabled(enabled)

    fun setAvoidDoubleSpatialization(enabled: Boolean) {
        viewModelScope.launch { settings.setAvoidDoubleSpatialization(enabled) }
    }

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
