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

    val installed: Flow<List<InstalledPlugin>> = context.pluginDataStore.data.map { decode(it[key]) }

    suspend fun snapshot(): List<InstalledPlugin> = installed.first()

    suspend fun upsert(plugin: InstalledPlugin) = update { list -> list.filterNot { it.id == plugin.id } + plugin }

    suspend fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    suspend fun setEnabled(id: String, enabled: Boolean) =
        update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }

    private suspend fun update(transform: (List<InstalledPlugin>) -> List<InstalledPlugin>) {
        context.pluginDataStore.edit { prefs -> prefs[key] = json.encodeToString<List<InstalledPlugin>>(transform(decode(prefs[key]))) }
    }

    private fun decode(raw: String?): List<InstalledPlugin> =
        raw?.let { runCatching { json.decodeFromString<List<InstalledPlugin>>(it) }.getOrNull() } ?: emptyList()
}
