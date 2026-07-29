package fm.rizx.player.data.plugin.install

import fm.rizx.player.core.error.AppError
import fm.rizx.player.domain.plugin.RegistryFile
import fm.rizx.player.domain.plugin.RegistryPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Fetches plugin registries (`plugins.json`) so the Store can list installable plugins (ADR 0014/0019).
 * The official Nuclear registry is always first; the user can add further registry URLs (same JSON
 * shape) and their plugins merge in — first registry wins on id collisions, so nobody can shadow an
 * official plugin. Keyless HTTP GETs; the last good merged result is cached in memory for offline
 * resilience. A broken extra registry is skipped, never fatal.
 */
class PluginRegistryClient(
    private val client: OkHttpClient,
    private val json: Json,
) {
    @Volatile
    private var cache: List<RegistryPlugin>? = null

    suspend fun fetch(extraRegistries: List<String> = emptyList()): List<RegistryPlugin> = withContext(Dispatchers.IO) {
        try {
            val merged = LinkedHashMap<String, RegistryPlugin>()
            for (plugin in fetchOne(REGISTRY_URL)) merged.putIfAbsent(plugin.id, plugin)
            for (url in extraRegistries) {
                runCatching { for (plugin in fetchOne(url)) merged.putIfAbsent(plugin.id, plugin) }
            }
            merged.values.toList().also { cache = it }
        } catch (e: IOException) {
            cache ?: throw AppError.Network(e.message ?: "registry unreachable", e)
        } catch (e: AppError) {
            cache ?: throw e
        } catch (e: Exception) {
            cache ?: throw AppError.ProviderFailure("PluginRegistry", e.message ?: "bad registry payload", e)
        }
    }

    private fun fetchOne(url: String): List<RegistryPlugin> =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw AppError.ProviderFailure("PluginRegistry", "HTTP ${response.code}")
            val body = response.body?.string() ?: "{}"
            json.decodeFromString<RegistryFile>(body).plugins
        }

    private companion object {
        const val REGISTRY_URL = "https://raw.githubusercontent.com/NuclearPlayer/plugin-registry/master/plugins.json"
    }
}
