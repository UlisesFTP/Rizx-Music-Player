package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Core app settings, persisted in DataStore (§7.3/§7.4). MVP covers the settings the app actually
 * uses — theme, the active metadata/streaming providers, and the stream resolver tunables. Keys keep
 * a `core.*`-style namespace room for future plugin settings. Each value is an observable [Flow] with
 * a sensible default until the user changes it.
 */
interface SettingsRepository {

    /** Light / dark / system. [ThemeMode.SYSTEM] (the default) follows the device's dark-mode setting. */
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)

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
     * Hi-Res output: force ExoPlayer's 32-bit float PCM output path so high-resolution audio (24-bit /
     * high-sample-rate local lossless files) isn't truncated to 16-bit. **Off by default.** Applies to the
     * next playback session (the audio sink is built once, at service start); it's a no-op for 16-bit/lossy
     * sources and on devices whose DAC can't do float.
     */
    val hiResOutput: Flow<Boolean>
    suspend fun setHiResOutput(enabled: Boolean)

    /**
     * Play the song's video muted behind the Now Playing artwork. **Off by default**: it pulls a second
     * stream on top of the audio, and for the auto-generated uploads that make up most of YouTube Music
     * the "video" is a still image, so it must be asked for rather than assumed.
     */
    val canvasEnabled: Flow<Boolean>
    suspend fun setCanvasEnabled(enabled: Boolean)

    /**
     * Show lyrics as a karaoke-style timed view (**on by default**) rather than plain scrolling prose.
     * Toggled from the lyrics screen itself, and persisted so it survives the next song: the timings come
     * from a community database and are occasionally wrong, and someone who has decided they'd rather
     * just read the text shouldn't have to say so again on every track.
     */
    val syncedLyricsMode: Flow<Boolean>
    suspend fun setSyncedLyricsMode(enabled: Boolean)

    /**
     * How many bytes of streamed audio to keep on disk so replays are instant and work offline.
     * Defaults to 512 MB (~100 songs). Read once when the cache is opened, so a change takes effect
     * from the next playback session — the audio cache takes its size limit at construction.
     */
    val audioCacheBytes: Flow<Long>
    suspend fun setAudioCacheBytes(bytes: Long)
}
