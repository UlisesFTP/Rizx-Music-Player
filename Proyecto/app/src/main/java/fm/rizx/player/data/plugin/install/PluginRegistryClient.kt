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
            // Filtered here rather than in the UI so the ones Rizx already does natively — or that
            // could not run at all — never enter the app: not in the store, not in an update check,
            // not in a search. A user-added registry is left alone: somebody who typed a URL in meant it.
            for (plugin in fetchOne(REGISTRY_URL)) {
                if (plugin.isHidden) continue
                merged.putIfAbsent(plugin.id, plugin)
            }
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
            val source = response.body?.source() ?: return@use emptyList()
            // Bound the read: a compromised/hostile registry answering with a multi-GB body would OOM here
            // (the official read is outside the per-registry runCatching). request(N+1) buffers at most N+1
            // bytes, so an oversized body fails instead of materializing fully in memory.
            if (source.request(MAX_REGISTRY_BYTES + 1L)) throw AppError.ProviderFailure("PluginRegistry", "registry response too large")
            json.decodeFromString<RegistryFile>(source.readUtf8()).plugins
        }

    private companion object {
        const val REGISTRY_URL = "https://raw.githubusercontent.com/NuclearPlayer/plugin-registry/master/plugins.json"
        const val MAX_REGISTRY_BYTES = 4L * 1024 * 1024
    }
}
