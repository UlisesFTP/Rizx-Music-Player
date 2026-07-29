package fm.rizx.player.data.plugin.bridge

import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.DiscoveryProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.RadioMixSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bridges a JS-registered discovery provider (upstream `getRecommendations(context, {variety, limit})`)
 * into the native [DiscoveryProvider] contract (ADR 0019). Also a [RadioMixSource], so a plugin's
 * discovery engine slots straight into the up-next selector and the queue refill — same seam as the
 * built-in engines, same Deezer fallback when it returns nothing.
 */
class JsDiscoveryProvider(
    override val id: String,
    override val name: String,
    override val version: String,
    override val pluginId: String?,
    private val uid: String,
    private val descriptorId: String,
    private val invoker: JsProviderInvoker,
    private val json: Json,
) : DiscoveryProvider, RadioMixSource {

    override val kind: ProviderKind = ProviderKind.DISCOVERY

    override suspend fun getRecommendations(context: List<Track>, variety: Double, limit: Int?): List<Track> {
        val args = buildJsonArray {
            add(buildJsonArray { for (track in context) add(JsModelMappers.trackToJson(track)) })
            add(buildJsonObject {
                put("variety", variety)
                limit?.let { put("limit", it) }
            })
        }.toString()
        val result = invoker.invoke(uid, "getRecommendations", args, timeoutMs = 20_000) ?: return emptyList()
        return JsModelMappers.parseTracks(result, descriptorId, json)
    }

    override suspend fun mixTracks(seed: Track): List<Track> = getRecommendations(listOf(seed))

    override suspend fun mixTracks(seed: Track, limit: Int): List<Track> =
        getRecommendations(listOf(seed), limit = limit)
}
