package fm.rizx.player.data.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import fm.rizx.player.domain.update.AppUpdate
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * What the update flow remembers between launches: when it last asked GitHub and what it heard, the
 * version the user chose to skip, and the version already announced by a notification (so one release
 * is announced once, not on every periodic check).
 */
interface AppUpdateStore {
    suspend fun lastCheckedAtMs(): Long
    suspend fun lastKnown(): AppUpdate?
    suspend fun saveCheck(update: AppUpdate?, atMs: Long)
    suspend fun skippedVersion(): String?
    suspend fun setSkippedVersion(version: String?)
    suspend fun notifiedVersion(): String?
    suspend fun setNotifiedVersion(version: String)
}

/** The store over its own small Preferences DataStore (`app_update`), separate from the settings file. */
class DataStoreAppUpdateStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) : AppUpdateStore {

    override suspend fun lastCheckedAtMs(): Long = read()[Keys.LAST_CHECKED_AT] ?: 0L

    override suspend fun lastKnown(): AppUpdate? =
        read()[Keys.LAST_KNOWN]?.let { raw -> runCatching { json.decodeFromString(AppUpdate.serializer(), raw) }.getOrNull() }

    override suspend fun saveCheck(update: AppUpdate?, atMs: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_CHECKED_AT] = atMs
            if (update == null) prefs.remove(Keys.LAST_KNOWN)
            else prefs[Keys.LAST_KNOWN] = json.encodeToString(AppUpdate.serializer(), update)
        }
    }

    override suspend fun skippedVersion(): String? = read()[Keys.SKIPPED_VERSION]

    override suspend fun setSkippedVersion(version: String?) {
        dataStore.edit { prefs -> if (version == null) prefs.remove(Keys.SKIPPED_VERSION) else prefs[Keys.SKIPPED_VERSION] = version }
    }

    override suspend fun notifiedVersion(): String? = read()[Keys.NOTIFIED_VERSION]

    override suspend fun setNotifiedVersion(version: String) {
        dataStore.edit { prefs -> prefs[Keys.NOTIFIED_VERSION] = version }
    }

    private suspend fun read(): Preferences = dataStore.data.first()

    private object Keys {
        val LAST_CHECKED_AT = longPreferencesKey("update.lastCheckedAtMs")
        val LAST_KNOWN = stringPreferencesKey("update.lastKnown")
        val SKIPPED_VERSION = stringPreferencesKey("update.skippedVersion")
        val NOTIFIED_VERSION = stringPreferencesKey("update.notifiedVersion")
    }
}
