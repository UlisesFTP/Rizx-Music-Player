package fm.rizx.player

import fm.rizx.player.data.local.settings.SettingsRepositoryImpl
import fm.rizx.player.domain.model.AudioQualityMode
import fm.rizx.player.domain.model.CanvasNetworkPolicy
import fm.rizx.player.domain.model.CanvasQuality
import fm.rizx.player.domain.model.LyricsVisualQuality
import fm.rizx.player.domain.model.RadioMode
import fm.rizx.player.domain.model.ThemeMode
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One in-memory [SettingsRepository] for every test that needs one.
 *
 * There used to be three hand-written copies (in the player, plugins and preferences ViewModel
 * tests) that disagreed about defaults, so adding a setting broke three files at once and any
 * default drift was invisible. Every key is a live [MutableStateFlow] seeded with the *production*
 * default, so a test can both read and write it and a test asserting a default is asserting the real
 * one.
 */
class FakeSettingsRepository : SettingsRepository {

    val themeModeFlow = MutableStateFlow(ThemeMode.SYSTEM)
    override val themeMode: Flow<ThemeMode> = themeModeFlow
    override suspend fun setThemeMode(mode: ThemeMode) { themeModeFlow.value = mode }

    val activeMetadataFlow = MutableStateFlow<String?>(null)
    override val activeMetadataProviderId: Flow<String?> = activeMetadataFlow
    override suspend fun setActiveMetadataProviderId(id: String?) { activeMetadataFlow.value = id }

    val activeStreamingFlow = MutableStateFlow<String?>(null)
    override val activeStreamingProviderId: Flow<String?> = activeStreamingFlow
    override suspend fun setActiveStreamingProviderId(id: String?) { activeStreamingFlow.value = id }

    val activeLyricsFlow = MutableStateFlow<String?>(null)
    override val activeLyricsProviderId: Flow<String?> = activeLyricsFlow
    override suspend fun setActiveLyricsProviderId(id: String?) { activeLyricsFlow.value = id }

    val streamExpiryFlow = MutableStateFlow(0L)
    override val streamExpiryMs: Flow<Long> = streamExpiryFlow
    override suspend fun setStreamExpiryMs(ms: Long) { streamExpiryFlow.value = ms }

    val streamRetriesFlow = MutableStateFlow(0)
    override val streamResolutionRetries: Flow<Int> = streamRetriesFlow
    override suspend fun setStreamResolutionRetries(retries: Int) { streamRetriesFlow.value = retries }

    val equalizerEnabledFlow = MutableStateFlow(false)
    override val equalizerEnabled: Flow<Boolean> = equalizerEnabledFlow
    override suspend fun setEqualizerEnabled(enabled: Boolean) { equalizerEnabledFlow.value = enabled }

    val equalizerBandsFlow = MutableStateFlow(emptyList<Int>())
    override val equalizerBandLevels: Flow<List<Int>> = equalizerBandsFlow
    override suspend fun setEqualizerBandLevels(levels: List<Int>) { equalizerBandsFlow.value = levels }

    val autoEqualizerFlow = MutableStateFlow(false)
    override val autoEqualizer: Flow<Boolean> = autoEqualizerFlow
    override suspend fun setAutoEqualizer(enabled: Boolean) { autoEqualizerFlow.value = enabled }

    val dataSaverFlow = MutableStateFlow(false)
    override val dataSaver: Flow<Boolean> = dataSaverFlow
    override suspend fun setDataSaver(enabled: Boolean) { dataSaverFlow.value = enabled }

    val crossfadeFlow = MutableStateFlow(false)
    override val crossfade: Flow<Boolean> = crossfadeFlow
    override suspend fun setCrossfade(enabled: Boolean) { crossfadeFlow.value = enabled }

    val gaplessFlow = MutableStateFlow(true)
    override val gapless: Flow<Boolean> = gaplessFlow
    override suspend fun setGapless(enabled: Boolean) { gaplessFlow.value = enabled }

    val normalizeFlow = MutableStateFlow(false)
    override val normalizeVolume: Flow<Boolean> = normalizeFlow
    override suspend fun setNormalizeVolume(enabled: Boolean) { normalizeFlow.value = enabled }

    val audioQualityModeFlow = MutableStateFlow(AudioQualityMode.STANDARD)
    override val audioQualityMode: Flow<AudioQualityMode> = audioQualityModeFlow
    override suspend fun setAudioQualityMode(mode: AudioQualityMode) { audioQualityModeFlow.value = mode }

    val losslessWifiOnlyFlow = MutableStateFlow(true)
    override val losslessWifiOnly: Flow<Boolean> = losslessWifiOnlyFlow
    override suspend fun setLosslessWifiOnly(enabled: Boolean) { losslessWifiOnlyFlow.value = enabled }

    val losslessDownloadFlow = MutableStateFlow(false)
    override val losslessDownload: Flow<Boolean> = losslessDownloadFlow
    override suspend fun setLosslessDownload(enabled: Boolean) { losslessDownloadFlow.value = enabled }

    val showTechnicalFormatFlow = MutableStateFlow(true)
    override val showTechnicalFormat: Flow<Boolean> = showTechnicalFormatFlow
    override suspend fun setShowTechnicalFormat(enabled: Boolean) { showTechnicalFormatFlow.value = enabled }

    val canvasFlow = MutableStateFlow(false)
    override val canvasEnabled: Flow<Boolean> = canvasFlow
    override suspend fun setCanvasEnabled(enabled: Boolean) { canvasFlow.value = enabled }

    val canvasNetworkFlow = MutableStateFlow(CanvasNetworkPolicy.UNMETERED_ONLY)
    override val canvasNetworkPolicy: Flow<CanvasNetworkPolicy> = canvasNetworkFlow
    override suspend fun setCanvasNetworkPolicy(policy: CanvasNetworkPolicy) { canvasNetworkFlow.value = policy }

    val canvasBatterySaverFlow = MutableStateFlow(false)
    override val canvasOnBatterySaver: Flow<Boolean> = canvasBatterySaverFlow
    override suspend fun setCanvasOnBatterySaver(allowed: Boolean) { canvasBatterySaverFlow.value = allowed }

    val canvasQualityFlow = MutableStateFlow(CanvasQuality.AUTO)
    override val canvasQuality: Flow<CanvasQuality> = canvasQualityFlow
    override suspend fun setCanvasQuality(quality: CanvasQuality) { canvasQualityFlow.value = quality }

    val canvasAppleFlow = MutableStateFlow(true)
    override val canvasAppleEnabled: Flow<Boolean> = canvasAppleFlow
    override suspend fun setCanvasAppleEnabled(enabled: Boolean) { canvasAppleFlow.value = enabled }

    val canvasYoutubeFlow = MutableStateFlow(true)
    override val canvasYoutubeEnabled: Flow<Boolean> = canvasYoutubeFlow
    override suspend fun setCanvasYoutubeEnabled(enabled: Boolean) { canvasYoutubeFlow.value = enabled }

    val syncedLyricsFlow = MutableStateFlow(true)
    override val syncedLyricsMode: Flow<Boolean> = syncedLyricsFlow
    override suspend fun setSyncedLyricsMode(enabled: Boolean) { syncedLyricsFlow.value = enabled }

    val audioCacheFlow = MutableStateFlow(SettingsRepositoryImpl.DEFAULT_AUDIO_CACHE_BYTES)
    override val audioCacheBytes: Flow<Long> = audioCacheFlow
    override suspend fun setAudioCacheBytes(bytes: Long) { audioCacheFlow.value = bytes }

    val regionalConsentFlow = MutableStateFlow<Boolean?>(null)
    override val recsRegionalConsent: Flow<Boolean?> = regionalConsentFlow
    override suspend fun setRecsRegionalConsent(consented: Boolean) { regionalConsentFlow.value = consented }

    val radioAlgorithmFlow = MutableStateFlow(SettingsRepositoryImpl.DEFAULT_RADIO_ALGORITHM)
    override val radioAlgorithm: Flow<RadioMode> = radioAlgorithmFlow
    override suspend fun setRadioAlgorithm(mode: RadioMode) { radioAlgorithmFlow.value = mode }

    val feedProviderFlow = MutableStateFlow(SettingsRepositoryImpl.DEFAULT_FEED_PROVIDER)
    override val feedProvider: Flow<String> = feedProviderFlow
    override suspend fun setFeedProvider(id: String) { feedProviderFlow.value = id }

    val lyricsQualityFlow = MutableStateFlow(LyricsVisualQuality.AUTOMATIC)
    override val lyricsVisualQuality: Flow<LyricsVisualQuality> = lyricsQualityFlow
    override suspend fun setLyricsVisualQuality(quality: LyricsVisualQuality) { lyricsQualityFlow.value = quality }
}
