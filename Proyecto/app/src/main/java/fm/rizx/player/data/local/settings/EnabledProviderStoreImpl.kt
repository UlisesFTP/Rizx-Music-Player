package fm.rizx.player.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import fm.rizx.player.domain.provider.EnabledProviderStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * [EnabledProviderStore] over a Preferences [DataStore]. Each provider gets a boolean key
 * `core.providers.enabled.<id>`; **absent = enabled** (opt-out model). Injecting the store keeps this
 * unit-testable against a temp-file DataStore.
 */
class EnabledProviderStoreImpl(
    private val dataStore: DataStore<Preferences>,
) : EnabledProviderStore {

    override fun isEnabled(id: String): Flow<Boolean> =
        dataStore.data.map { it[key(id)] ?: true }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        dataStore.edit { it[key(id)] = enabled }
    }

    override suspend fun snapshot(ids: Collection<String>): Map<String, Boolean> {
        val prefs = dataStore.data.first()
        return ids.associateWith { prefs[key(it)] ?: true }
    }

    private fun key(id: String) = booleanPreferencesKey("core.providers.enabled.$id")
}
