package fm.rizx.player.data.plugin.bridge

import fm.rizx.player.data.plugin.engine.PluginException
import fm.rizx.player.data.plugin.engine.QuickJsEngine
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.StreamingProvider
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add

/**
 * Bridges a JS-registered streaming provider descriptor into the Kotlin [StreamingProvider] contract
 * (ADR 0014). Nuclear's V1 shape: `searchForTrack(artist, title) → StreamCandidate[]` and
 * `getStreamUrl(candidateId) → Stream`. Results map back defensively via [JsModelMappers].
 */
class JsStreamingProvider(
    override val id: String,
    override val name: String,
    override val version: String,
    override val pluginId: String?,
    private val uid: String,
    private val descriptorId: String,
    private val engine: QuickJsEngine,
    private val json: Json,
) : StreamingProvider {

    override val kind: ProviderKind = ProviderKind.STREAMING

    override suspend fun searchForTrack(track: Track): List<StreamCandidate> {
        val artist = track.artists.firstOrNull()?.name.orEmpty()
        val args = buildJsonArray { add(artist); add(track.title) }.toString()
        val result = invoke("searchForTrack", args) ?: return emptyList()
        return JsModelMappers.parseStreamCandidates(result, descriptorId, json)
    }

    override suspend fun getStreamUrl(candidate: StreamCandidate): Stream {
        val args = buildJsonArray { add(candidate.id) }.toString()
        val result = invoke("getStreamUrl", args) ?: throw PluginException("$name: no stream for ${candidate.id}")
        return JsModelMappers.parseStream(result, descriptorId, json)
            ?: throw PluginException("$name: invalid stream for ${candidate.id}")
    }

    private suspend fun invoke(method: String, argsJson: String): String? =
        engine.evalCaptured("globalThis.__rizx.invokeAndCapture(${enc(uid)}, ${enc(method)}, ${enc(argsJson)})")

    private fun enc(s: String): String = json.encodeToString(String.serializer(), s)
}
