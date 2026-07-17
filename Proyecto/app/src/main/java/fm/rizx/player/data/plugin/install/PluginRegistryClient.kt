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
 * Fetches Nuclear's official plugin registry (`plugins.json`) so the Store can list installable plugins
 * (ADR 0014). Keyless HTTP GET; the last good result is cached in memory for offline resilience.
 */
class PluginRegistryClient(
    private val client: OkHttpClient,
    private val json: Json,
) {
    @Volatile
    private var cache: List<RegistryPlugin>? = null

    suspend fun fetch(): List<RegistryPlugin> = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(REGISTRY_URL).build()).execute().use { response ->
                if (!response.isSuccessful) return@use cache ?: throw AppError.ProviderFailure("PluginRegistry", "HTTP ${response.code}")
                val body = response.body?.string() ?: "{}"
                json.decodeFromString<RegistryFile>(body).plugins.also { cache = it }
            }
        } catch (e: IOException) {
            cache ?: throw AppError.Network(e.message ?: "registry unreachable", e)
        } catch (e: AppError) {
            throw e
        } catch (e: Exception) {
            cache ?: throw AppError.ProviderFailure("PluginRegistry", e.message ?: "bad registry payload", e)
        }
    }

    private companion object {
        const val REGISTRY_URL = "https://raw.githubusercontent.com/NuclearPlayer/plugin-registry/master/plugins.json"
    }
}
