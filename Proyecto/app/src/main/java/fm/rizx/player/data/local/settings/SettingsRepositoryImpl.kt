package fm.rizx.player.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import fm.rizx.player.domain.model.PlaybackResolverSettings
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

    override val darkTheme: Flow<Boolean> = dataStore.data.map { it[Keys.DARK_THEME] ?: true }

    override suspend fun setDarkTheme(dark: Boolean) {
        dataStore.edit { it[Keys.DARK_THEME] = dark }
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

    // Off by default: a canvas pulls a video stream on top of the audio one, so it has to be asked for.
    override val canvasEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.CANVAS] ?: false }

    override suspend fun setCanvasEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CANVAS] = enabled }
    }

    // `core.*` namespacing leaves room for future `plugin.*` settings (§7.4).
    private object Keys {
        val DARK_THEME = booleanPreferencesKey("core.ui.darkTheme")
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
        val CANVAS = booleanPreferencesKey("core.ui.canvas")
    }

    private companion object {
        val DEFAULTS = PlaybackResolverSettings()

        /** Parses the comma-separated millibel band levels; ignores malformed entries. */
        fun decodeLevels(csv: String?): List<Int> =
            csv?.takeIf { it.isNotBlank() }?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
    }
}
