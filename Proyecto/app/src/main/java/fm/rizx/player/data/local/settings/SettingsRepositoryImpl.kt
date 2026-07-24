package fm.rizx.player.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import fm.rizx.player.domain.model.PlaybackResolverSettings
import fm.rizx.player.domain.model.ThemeMode
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [SettingsRepository] over a Preferences [DataStore]. Injecting the `DataStore` (rather than a
 * `Context`) keeps this unit-testable against a temp-file store. Resolver defaults come from
 * [PlaybackResolverSettings] so the app and settings agree.
 */
class SettingsRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    // A stored themeMode wins; otherwise migrate the legacy darkTheme boolean (true→DARK, false→LIGHT);
    // a brand-new install has neither → SYSTEM (follow the device), the new default.
    override val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        when (prefs[Keys.THEME_MODE]) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            "system" -> ThemeMode.SYSTEM
            else -> when (prefs[Keys.DARK_THEME]) {
                true -> ThemeMode.DARK
                false -> ThemeMode.LIGHT
                null -> ThemeMode.SYSTEM
            }
        }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name.lowercase() }
    }

    override val activeMetadataProviderId: Flow<String?> = dataStore.data.map { it[Keys.ACTIVE_METADATA] }

    override suspend fun setActiveMetadataProviderId(id: String?) {
        dataStore.edit { prefs -> if (id == null) prefs.remove(Keys.ACTIVE_METADATA) else prefs[Keys.ACTIVE_METADATA] = id }
    }

    override val activeStreamingProviderId: Flow<String?> = dataStore.data.map { it[Keys.ACTIVE_STREAMING] }

    override suspend fun setActiveStreamingProviderId(id: String?) {
        dataStore.edit { prefs -> if (id == null) prefs.remove(Keys.ACTIVE_STREAMING) else prefs[Keys.ACTIVE_STREAMING] = id }
    }

    override val streamExpiryMs: Flow<Long> =
        dataStore.data.map { it[Keys.STREAM_EXPIRY_MS] ?: DEFAULTS.streamExpiryMs }

    override suspend fun setStreamExpiryMs(ms: Long) {
        dataStore.edit { it[Keys.STREAM_EXPIRY_MS] = ms }
    }

    override val streamResolutionRetries: Flow<Int> =
        dataStore.data.map { it[Keys.STREAM_RETRIES] ?: DEFAULTS.streamResolutionRetries }

    override suspend fun setStreamResolutionRetries(retries: Int) {
        dataStore.edit { it[Keys.STREAM_RETRIES] = retries }
    }

    override val equalizerEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.EQ_ENABLED] ?: false }

    override suspend fun setEqualizerEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.EQ_ENABLED] = enabled }
    }

    override val equalizerBandLevels: Flow<List<Int>> =
        dataStore.data.map { prefs -> decodeLevels(prefs[Keys.EQ_BANDS]) }

    override suspend fun setEqualizerBandLevels(levels: List<Int>) {
        dataStore.edit { it[Keys.EQ_BANDS] = levels.joinToString(",") }
    }

    override val dataSaver: Flow<Boolean> = dataStore.data.map { it[Keys.DATA_SAVER] ?: false }

    override suspend fun setDataSaver(enabled: Boolean) {
        dataStore.edit { it[Keys.DATA_SAVER] = enabled }
    }

    override val crossfade: Flow<Boolean> = dataStore.data.map { it[Keys.CROSSFADE] ?: false }

    override suspend fun setCrossfade(enabled: Boolean) {
        dataStore.edit { it[Keys.CROSSFADE] = enabled }
    }

    override val gapless: Flow<Boolean> = dataStore.data.map { it[Keys.GAPLESS] ?: true }

    override suspend fun setGapless(enabled: Boolean) {
        dataStore.edit { it[Keys.GAPLESS] = enabled }
    }

    override val normalizeVolume: Flow<Boolean> = dataStore.data.map { it[Keys.NORMALIZE_VOLUME] ?: false }

    override suspend fun setNormalizeVolume(enabled: Boolean) {
        dataStore.edit { it[Keys.NORMALIZE_VOLUME] = enabled }
    }

    override val hiResOutput: Flow<Boolean> = dataStore.data.map { it[Keys.HI_RES_OUTPUT] ?: false }

    override suspend fun setHiResOutput(enabled: Boolean) {
        dataStore.edit { it[Keys.HI_RES_OUTPUT] = enabled }
    }

    // Off by default: a canvas pulls a video stream on top of the audio one, so it has to be asked for.
    override val canvasEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.CANVAS] ?: false }

    override suspend fun setCanvasEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CANVAS] = enabled }
    }

    // On by default: when a track has timings, the synced view is the point of the feature.
    override val syncedLyricsMode: Flow<Boolean> = dataStore.data.map { it[Keys.SYNCED_LYRICS] ?: true }

    override suspend fun setSyncedLyricsMode(enabled: Boolean) {
        dataStore.edit { it[Keys.SYNCED_LYRICS] = enabled }
    }

    override val audioCacheBytes: Flow<Long> =
        dataStore.data.map { it[Keys.AUDIO_CACHE_BYTES] ?: DEFAULT_AUDIO_CACHE_BYTES }

    override suspend fun setAudioCacheBytes(bytes: Long) {
        dataStore.edit { it[Keys.AUDIO_CACHE_BYTES] = bytes }
    }

    // `core.*` namespacing leaves room for future `plugin.*` settings (§7.4).
    private object Keys {
        // DARK_THEME is legacy — kept only so an existing install's old choice migrates into THEME_MODE.
        val DARK_THEME = booleanPreferencesKey("core.ui.darkTheme")
        val THEME_MODE = stringPreferencesKey("core.ui.themeMode")
        val ACTIVE_METADATA = stringPreferencesKey("core.providers.active.metadata")
        val ACTIVE_STREAMING = stringPreferencesKey("core.providers.active.streaming")
        val STREAM_EXPIRY_MS = longPreferencesKey("core.playback.streamExpiryMs")
        val STREAM_RETRIES = intPreferencesKey("core.playback.streamResolutionRetries")
        val EQ_ENABLED = booleanPreferencesKey("core.audio.eq.enabled")
        val EQ_BANDS = stringPreferencesKey("core.audio.eq.bands")
        val DATA_SAVER = booleanPreferencesKey("core.data.dataSaver")
        val CROSSFADE = booleanPreferencesKey("core.audio.crossfade")
        val GAPLESS = booleanPreferencesKey("core.audio.gapless")
        val NORMALIZE_VOLUME = booleanPreferencesKey("core.audio.normalizeVolume")
        val HI_RES_OUTPUT = booleanPreferencesKey("core.audio.hiResOutput")
        val CANVAS = booleanPreferencesKey("core.ui.canvas")
        val SYNCED_LYRICS = booleanPreferencesKey("core.ui.syncedLyrics")
        val AUDIO_CACHE_BYTES = longPreferencesKey("core.cache.audioBytes")
    }

    companion object {
        /** 512 MB ≈ 100 full songs at 3-8 MB each. */
        const val DEFAULT_AUDIO_CACHE_BYTES = 512L * 1024 * 1024

        private val DEFAULTS = PlaybackResolverSettings()

        /** Parses the comma-separated millibel band levels; ignores malformed entries. */
        private fun decodeLevels(csv: String?): List<Int> =
            csv?.takeIf { it.isNotBlank() }?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
    }
}
