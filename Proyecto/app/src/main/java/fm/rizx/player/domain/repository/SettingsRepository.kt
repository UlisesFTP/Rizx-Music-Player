package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.AudioQualityMode
import fm.rizx.player.domain.model.CanvasNetworkPolicy
import fm.rizx.player.domain.model.DownloadFormat
import fm.rizx.player.domain.model.CanvasQuality
import fm.rizx.player.domain.model.LyricsVisualQuality
import fm.rizx.player.domain.model.RadioMode
import fm.rizx.player.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    /** Which lyrics source leads the chain. Persisted like the others so a pick survives a restart. */
    val activeLyricsProviderId: Flow<String?>
    suspend fun setActiveLyricsProviderId(id: String?)

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
     * Automatic equalizer: a curve per song, derived from its genre and then refined by measuring the
     * song's own spectrum. **Off by default** — it takes the equalizer over while it is on, and a player
     * that silently re-tunes every track is something the user has to ask for. The manual band levels
     * above are left untouched and come back when it is switched off.
     */
    val autoEqualizer: Flow<Boolean>
    suspend fun setAutoEqualizer(enabled: Boolean)

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
     * How hard to work for audio quality: conservative, best compressed, or try for a verified FLAC
     * first. Replaces the old `hiResOutput` boolean, whose stored value migrates in
     * (`false → STANDARD`, `true → BEST_AVAILABLE`) — **never** into
     * [AudioQualityMode.LOSSLESS_PREFERRED], which has to be chosen.
     */
    val audioQualityMode: Flow<AudioQualityMode>
    suspend fun setAudioQualityMode(mode: AudioQualityMode)

    /**
     * Hi-Res output: force ExoPlayer's 32-bit float PCM output path so high-resolution audio (24-bit /
     * high-sample-rate local lossless files) isn't truncated to 16-bit. Applies to the next playback
     * session (the audio sink is built once, at service start); it's a no-op for 16-bit/lossy sources and
     * on devices whose DAC can't do float.
     *
     * Now derived rather than stored, so the callers that only care about "better than standard" keep
     * working unchanged. Anything that must distinguish *which* better — clearing the resolved-URL cache
     * when the mode changes, for instance — has to read [audioQualityMode] instead, since both non-standard
     * modes map to `true` here.
     */
    @Deprecated("Read audioQualityMode; this collapses two distinct modes into one flag.")
    val hiResOutput: Flow<Boolean>
        get() = audioQualityMode.map { it.prefersBestCompressed }

    /**
     * Only look for a lossless file on an unmetered connection. **On by default**: the files measured
     * against the reference index run 25-27 MB each, which is an order of magnitude more than the
     * compressed stream it replaces.
     */
    val losslessWifiOnly: Flow<Boolean>
    suspend fun setLosslessWifiOnly(enabled: Boolean)

    /**
     * What a download saves: the taggable original (default), the best compressed rendition (Opus),
     * an LAME 320 CBR MP3, or a verified community FLAC when one exists. Replaces the old
     * `losslessDownload` boolean, whose stored value migrates in (`true → FLAC`).
     */
    val downloadFormat: Flow<DownloadFormat>
    suspend fun setDownloadFormat(format: DownloadFormat)

    /** Show the codec/depth/rate line under the player. On by default — it is the honest part of this feature. */
    val showTechnicalFormat: Flow<Boolean>
    suspend fun setShowTechnicalFormat(enabled: Boolean)

    /**
     * Play the song's video muted behind the Now Playing artwork. **Off by default**: it pulls a second
     * stream on top of the audio, and for the auto-generated uploads that make up most of YouTube Music
     * the "video" is a still image, so it must be asked for rather than assumed.
     */
    val canvasEnabled: Flow<Boolean>
    suspend fun setCanvasEnabled(enabled: Boolean)

    /**
     * Which connections a canvas may be fetched over. Defaults to [CanvasNetworkPolicy.UNMETERED_ONLY] —
     * a canvas is a *second* stream on top of the audio one, so spending mobile data on it has to be
     * asked for. Metered is decided by the system's own flag, not by the radio, so a phone hotspot
     * counts as mobile data.
     */
    val canvasNetworkPolicy: Flow<CanvasNetworkPolicy>
    suspend fun setCanvasNetworkPolicy(policy: CanvasNetworkPolicy)

    /**
     * Whether a canvas may still play in the device's power-save mode. **Off by default**: decoding a
     * second video stream is exactly what power-save mode exists to stop.
     */
    val canvasOnBatterySaver: Flow<Boolean>
    suspend fun setCanvasOnBatterySaver(allowed: Boolean)

    /**
     * How large a canvas to ask for. Meaningful because Apple's motion artwork is one HLS URL holding a
     * ladder up to 2160² — the cap is what picks the rung. Mobile data and a low-RAM device still
     * overrule it (`CanvasGate.quality`).
     */
    val canvasQuality: Flow<CanvasQuality>
    suspend fun setCanvasQuality(quality: CanvasQuality)

    /** Apple's motion album artwork as a canvas source. On by default — it is the one that loops. */
    val canvasAppleEnabled: Flow<Boolean>
    suspend fun setCanvasAppleEnabled(enabled: Boolean)

    /**
     * YouTube's music video as the canvas fallback. On by default, and separately switchable because it
     * is the source that can be *wrong*: Apple either has this album's loop or it hasn't, whereas
     * YouTube is a search over a catalogue full of near-misses.
     */
    val canvasYoutubeEnabled: Flow<Boolean>
    suspend fun setCanvasYoutubeEnabled(enabled: Boolean)

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

    /**
     * Regional-recommendations consent: `null` = never asked (the For-you consent card shows),
     * `true` = use the device country (SIM/locale — no location) for regional charts, `false` =
     * declined, global variants only. The country itself is resolved on demand and never stored.
     */
    val recsRegionalConsent: Flow<Boolean?>
    suspend fun setRecsRegionalConsent(consented: Boolean)

    /**
     * Which engine keeps a single-track play going when you hit next: [RadioMode.YOUTUBE] follows YT
     * Music's own autoplay for that song, [RadioMode.ARTIST] follows the metadata provider's artist
     * radio (Deezer). Applies to anything started from one song — the Home feed, Search, and the
     * player's own radio button.
     */
    val radioAlgorithm: Flow<RadioMode>
    suspend fun setRadioAlgorithm(mode: RadioMode)

    /**
     * Which source fills the Home feed: the id of one registered dashboard provider, or
     * `SettingsRepositoryImpl.FEED_PROVIDER_ALL` to blend every enabled one. Defaults to Deezer — the
     * only source that fills all four sections on its own.
     *
     * A raw id rather than an enum, because the options come from the provider registry: a plugin's
     * dashboard becomes selectable the moment it registers, with no code change here.
     */
    val feedProvider: Flow<String>
    suspend fun setFeedProvider(id: String)

    /**
     * How hard the karaoke lyrics view is allowed to work. Defaults to
     * [LyricsVisualQuality.AUTOMATIC], which turns the halo off and halves the frame rate by itself on a
     * device in power-save mode or short on RAM. This is the one screen in the app that asks for a frame
     * every frame, so it gets a switch (`docs/adr/0016-karaoke-frame-loop.md`).
     */
    val lyricsVisualQuality: Flow<LyricsVisualQuality>
    suspend fun setLyricsVisualQuality(quality: LyricsVisualQuality)
}
