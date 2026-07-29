package fm.rizx.player.data.plugin

import fm.rizx.player.data.plugin.install.PluginInstaller
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Backs the plugin sandbox's `api.Settings` / `api.Storage` with one small JSON object per plugin per
 * scope (`settings.json` / `storage.json` in the plugin's install dir — ADR 0019). Values are opaque
 * JSON-encoded strings; the sandbox owns their meaning. Writes are atomic (tmp + rename) and
 * synchronous — these files are tiny and written rarely (a token, a username), and staying on the
 * caller's thread keeps the engine's single-thread serialization as the only concurrency story.
 *
 * The files deliberately live inside the plugin dir: uninstall's `deleteRecursively()` wipes them,
 * while the installer preserves them across updates.
 */
class PluginKvStore(
    private val pluginsRoot: File,
    private val json: Json,
) {
    /** (pluginId, scope) → live map. Loaded lazily, then the file only mirrors this. */
    private val cache = ConcurrentHashMap<String, MutableMap<String, String>>()

    fun get(pluginId: String, scope: String, key: String): String? =
        synchronized(this) { load(pluginId, scope)[key] }

    fun set(pluginId: String, scope: String, key: String, valueJson: String) {
        synchronized(this) {
            val map = load(pluginId, scope)
            map[key] = valueJson
            persist(pluginId, scope, map)
        }
    }

    fun remove(pluginId: String, scope: String, key: String) {
        synchronized(this) {
            val map = load(pluginId, scope)
            if (map.remove(key) != null) persist(pluginId, scope, map)
        }
    }

    /** Drops the in-memory mirror (uninstall path — the dir itself is deleted by the caller). */
    fun evict(pluginId: String) {
        cache.keys.removeAll { it.startsWith("$pluginId/") }
    }

    private fun load(pluginId: String, scope: String): MutableMap<String, String> =
        cache.getOrPut("$pluginId/$scope") {
            val map = mutableMapOf<String, String>()
            runCatching {
                val file = fileFor(pluginId, scope)
                if (file.isFile) {
                    val obj = json.parseToJsonElement(file.readText()) as? JsonObject
                    obj?.forEach { (k, v) -> map[k] = v.toString() }
                }
            }
            map
        }

    private fun persist(pluginId: String, scope: String, map: Map<String, String>) {
        runCatching {
            val file = fileFor(pluginId, scope)
            file.parentFile?.mkdirs()
            val body = map.entries.joinToString(",", prefix = "{", postfix = "}") { (k, v) ->
                "${json.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.JsonPrimitive(k))}:$v"
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(file)) {
                file.writeText(body)
                tmp.delete()
            }
        }
    }

    private fun fileFor(pluginId: String, scope: String): File {
        val name = if (scope == SCOPE_SETTINGS) PluginInstaller.SETTINGS_FILE else PluginInstaller.STORAGE_FILE
        return File(File(pluginsRoot, pluginId), name)
    }

    companion object {
        const val SCOPE_SETTINGS = "settings"
        const val SCOPE_STORAGE = "storage"
    }
}
