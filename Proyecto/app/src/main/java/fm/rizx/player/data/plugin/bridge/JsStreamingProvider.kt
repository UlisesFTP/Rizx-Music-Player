package fm.rizx.player.data.plugin.bridge

import fm.rizx.player.data.plugin.engine.PluginException
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.StreamingProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add

/**
 * Bridges a JS-registered streaming provider descriptor into the Kotlin [StreamingProvider] contract
 * (ADR 0014/0019). Prefers Nuclear's V2 shape when the plugin has it — `searchForTrackV2(track)`
 * receives the full track (album and duration included) — falling back to V1's
 * `searchForTrack(artist, title)`. Phase 2 stays `getStreamUrl(candidateId) → Stream`. Results map
 * back defensively via [JsModelMappers].
 */
class JsStreamingProvider(
    override val id: String,
    override val name: String,
    override val version: String,
    override val pluginId: String?,
    private val uid: String,
    private val descriptorId: String,
    private val methods: Set<String> = emptySet(),
    private val invoker: JsProviderInvoker,
    private val json: Json,
) : StreamingProvider {

    override val kind: ProviderKind = ProviderKind.STREAMING

    override suspend fun searchForTrack(track: Track): List<StreamCandidate> {
        val result = if ("searchForTrackV2" in methods) {
            val args = buildJsonArray { add(JsModelMappers.trackToJson(track)) }.toString()
            invoke("searchForTrackV2", args)
        } else {
            val artist = track.artists.firstOrNull()?.name.orEmpty()
            val args = buildJsonArray { add(artist); add(track.title) }.toString()
            invoke("searchForTrack", args)
        } ?: return emptyList()
        return JsModelMappers.parseStreamCandidates(result, descriptorId, json)
    }

    override suspend fun getStreamUrl(candidate: StreamCandidate): Stream {
        val method = if ("getStreamUrlV2" in methods) "getStreamUrlV2" else "getStreamUrl"
        val args = buildJsonArray { add(candidate.id) }.toString()
        val result = invoke(method, args) ?: throw PluginException("$name: no stream for ${candidate.id}")
        return JsModelMappers.parseStream(result, descriptorId, json)
            ?: throw PluginException("$name: invalid stream for ${candidate.id}")
    }

    private suspend fun invoke(method: String, argsJson: String): String? =
        invoker.invoke(uid, method, argsJson, timeoutMs = 20_000)
}
