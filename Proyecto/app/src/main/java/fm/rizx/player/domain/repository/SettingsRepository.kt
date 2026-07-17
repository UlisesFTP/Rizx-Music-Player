package fm.rizx.player.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Core app settings, persisted in DataStore (§7.3/§7.4). MVP covers the settings the app actually
 * uses — theme, the active metadata/streaming providers, and the stream resolver tunables. Keys keep
 * a `core.*`-style namespace room for future plugin settings. Each value is an observable [Flow] with
 * a sensible default until the user changes it.
 */
interface SettingsRepository {

    val darkTheme: Flow<Boolean>
    suspend fun setDarkTheme(dark: Boolean)

    val activeMetadataProviderId: Flow<String?>
    suspend fun setActiveMetadataProviderId(id: String?)

    val activeStreamingProviderId: Flow<String?>
    suspend fun setActiveStreamingProviderId(id: String?)

    val streamExpiryMs: Flow<Long>
    suspend fun setStreamExpiryMs(ms: Long)

    val streamResolutionRetries: Flow<Int>
    suspend fun setStreamResolutionRetries(retries: Int)

    /** Equalizer on/off (Phase 15). */
    val equalizerEnabled: Flow<Boolean>
    suspend fun setEqualizerEnabled(enabled: Boolean)

    /** Per-band levels in millibels (device-band order); empty = not yet configured / flat. */
    val equalizerBandLevels: Flow<List<Int>>
    suspend fun setEqualizerBandLevels(levels: List<Int>)

    /**
     * Data saver: when on **and the device is on cellular**, the streaming provider prefers a lower
     * bitrate (Wi-Fi / good signal always stays at max). Off by default.
     */
    val dataSaver: Flow<Boolean>
    suspend fun setDataSaver(enabled: Boolean)

    /** Crossfade a short volume fade at track transitions (approximation, single-player). Off by default. */
    val crossfade: Flow<Boolean>
    suspend fun setCrossfade(enabled: Boolean)

    /**
     * Gapless playback. On (default) = seamless (ExoPlayer's automatic gapless); off = a brief fade
     * between tracks (a perceptible gap), since ExoPlayer exposes no gapless on/off switch.
     */
    val gapless: Flow<Boolean>
    suspend fun setGapless(enabled: Boolean)

    /** Normalize volume via a fixed [android.media.audiofx.LoudnessEnhancer] gain. Off by default. */
    val normalizeVolume: Flow<Boolean>
    suspend fun setNormalizeVolume(enabled: Boolean)

    /**
     * Play the song's video muted behind the Now Playing artwork. **Off by default**: it pulls a second
     * stream on top of the audio, and for the auto-generated uploads that make up most of YouTube Music
     * the "video" is a still image, so it must be asked for rather than assumed.
     */
    val canvasEnabled: Flow<Boolean>
    suspend fun setCanvasEnabled(enabled: Boolean)
}
