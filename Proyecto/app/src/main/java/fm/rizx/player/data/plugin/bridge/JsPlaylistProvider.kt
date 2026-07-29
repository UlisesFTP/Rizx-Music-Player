package fm.rizx.player.data.plugin.bridge

import fm.rizx.player.core.error.AppError
import fm.rizx.player.domain.model.PlaylistPreview
import fm.rizx.player.domain.provider.PlaylistProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add

/**
 * Bridges a JS-registered playlists provider (`matchesUrl(url)` / `fetchPlaylistByUrl(url)`) into the
 * native [PlaylistProvider] contract (ADR 0019).
 *
 * [canHandle] must be cheap and synchronous while the plugin's `matchesUrl` is async JS — so it uses a
 * **domain heuristic**: tokens from the descriptor/plugin id matched against the URL host (a
 * `soundcloud-playlists` descriptor claims soundcloud.com links). Native providers register first and
 * keep priority; [fetchPlaylist] still asks the plugin's own `matchesUrl` before fetching, so a false
 * positive fails cleanly instead of importing garbage.
 */
class JsPlaylistProvider(
    override val id: String,
    override val name: String,
    override val version: String,
    override val pluginId: String?,
    private val uid: String,
    private val descriptorId: String,
    private val methods: Set<String>,
    private val invoker: JsProviderInvoker,
    private val json: Json,
) : PlaylistProvider {

    override val kind: ProviderKind = ProviderKind.PLAYLISTS

    private val hostTokens: List<String> =
        (descriptorId.split(NON_ALPHA) + (pluginId ?: "").split(NON_ALPHA))
            .map { it.lowercase() }
            .filter { it.length >= 4 && it !in GENERIC_TOKENS }
            .distinct()

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http") && hostTokens.any { it in lower }
    }

    override suspend fun fetchPlaylist(url: String): PlaylistPreview {
        if ("matchesUrl" in methods) {
            val matches = invoke("matchesUrl", url)
            if (matches?.trim() != "true") {
                throw AppError.ProviderFailure(name, "not a $name playlist URL")
            }
        }
        val method = if ("fetchPlaylistByUrl" in methods) "fetchPlaylistByUrl" else "fetchPlaylist"
        val result = invoke(method, url)
            ?: throw AppError.ProviderFailure(name, "playlist fetch returned nothing")
        return JsModelMappers.parsePlaylistPreview(result, descriptorId, url, json)
            ?: throw AppError.ProviderFailure(name, "playlist fetch returned an unreadable result")
    }

    private suspend fun invoke(method: String, url: String): String? {
        val args = buildJsonArray { add(url) }.toString()
        return invoker.invoke(uid, method, args, timeoutMs = 30_000)
    }

    private companion object {
        val NON_ALPHA = Regex("[^A-Za-z]+")

        /** Words that appear in ids without naming a service. */
        val GENERIC_TOKENS = setOf("nuclear", "plugin", "playlist", "playlists", "provider", "dashboard", "metadata", "streaming", "something")
    }
}
