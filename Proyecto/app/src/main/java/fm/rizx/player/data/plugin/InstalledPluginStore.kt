package fm.rizx.player.data.plugin

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import fm.rizx.player.domain.plugin.InstalledPlugin
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.pluginDataStore by preferencesDataStore("rizx_plugins")

/**
 * Persists the list of installed plugins (ADR 0014) — this app's mirror of Nuclear's `plugins.json`, so
 * downloaded plugins and their enabled state survive restarts. A single JSON-encoded preference key.
 */
class InstalledPluginStore(
    private val context: Context,
    private val json: Json,
) {
    private val key = stringPreferencesKey("installed")
    private val registriesKey = stringPreferencesKey("registries")

    val installed: Flow<List<InstalledPlugin>> = context.pluginDataStore.data.map { decode(it[key]) }

    /** User-added plugin registry URLs (the official one is implicit and always first). */
    val registries: Flow<List<String>> = context.pluginDataStore.data.map { decodeRegistries(it[registriesKey]) }

    suspend fun registriesSnapshot(): List<String> = registries.first()

    suspend fun addRegistry(url: String) = context.pluginDataStore.edit { prefs ->
        val current = decodeRegistries(prefs[registriesKey])
        if (url !in current) prefs[registriesKey] = json.encodeToString<List<String>>(current + url)
    }

    suspend fun removeRegistry(url: String) = context.pluginDataStore.edit { prefs ->
        prefs[registriesKey] = json.encodeToString<List<String>>(decodeRegistries(prefs[registriesKey]) - url)
    }

    private fun decodeRegistries(raw: String?): List<String> =
        raw?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() } ?: emptyList()

    suspend fun snapshot(): List<InstalledPlugin> = installed.first()

    suspend fun upsert(plugin: InstalledPlugin) = update { list -> list.filterNot { it.id == plugin.id } + plugin }

    suspend fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    suspend fun setEnabled(id: String, enabled: Boolean) =
        update { list ->
            list.map {
                // Re-enabling clears a quarantine — the user is explicitly giving it another chance.
                if (it.id == id) {
                    if (enabled) it.copy(enabled = true, health = "", lastError = "")
                    else it.copy(enabled = false)
                } else it
            }
        }

    /** Records a health verdict (quarantine) and disables the plugin in one write. */
    suspend fun setHealth(id: String, health: String, lastError: String) =
        update { list ->
            list.map {
                if (it.id == id) it.copy(enabled = false, health = health, lastError = lastError) else it
            }
        }

    private suspend fun update(transform: (List<InstalledPlugin>) -> List<InstalledPlugin>) {
        context.pluginDataStore.edit { prefs -> prefs[key] = json.encodeToString<List<InstalledPlugin>>(transform(decode(prefs[key]))) }
    }

    private fun decode(raw: String?): List<InstalledPlugin> =
        raw?.let { runCatching { json.decodeFromString<List<InstalledPlugin>>(it) }.getOrNull() } ?: emptyList()
}
