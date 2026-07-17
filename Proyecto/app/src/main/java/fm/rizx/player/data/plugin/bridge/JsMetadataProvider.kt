package fm.rizx.player.data.plugin.bridge

import fm.rizx.player.data.plugin.engine.QuickJsEngine
import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bridges a JS-registered metadata provider descriptor into the Kotlin [MetadataProvider] contract
 * (ADR 0014). Each call marshals arguments to JSON, invokes the plugin's async method through the
 * [QuickJsEngine], and maps the JSON result back defensively via [JsModelMappers]. Failures surface as
 * [fm.rizx.player.data.plugin.engine.PluginException] (a plain Exception) so the repository/resolver
 * treat a broken plugin as an isolated failure, never a crash.
 *
 * @param uid registry-unique id (`pluginId:descriptorId`); [descriptorId] is the plugin-declared
 *   provider id used as the fallback `source.provider` for items that omit their own.
 */
class JsMetadataProvider(
    override val id: String,
    override val name: String,
    override val version: String,
    override val pluginId: String?,
    private val uid: String,
    private val descriptorId: String,
    override val searchCapabilities: Set<SearchCapability>,
    private val methods: Set<String>,
    private val engine: QuickJsEngine,
    private val json: Json,
) : MetadataProvider {

    override val kind: ProviderKind = ProviderKind.METADATA

    override suspend fun search(params: SearchParams): SearchResults {
        val argsJson = buildArgs(params)
        return try {
            if (SearchCapability.UNIFIED in searchCapabilities || "search" in methods) {
                callSearch("search", argsJson)
            } else {
                // Merge per-category searches (e.g. Discogs exposes searchArtists/searchAlbums).
                var acc = SearchResults()
                if ("searchArtists" in methods) acc = acc.copy(artists = callSearch("searchArtists", argsJson).artists)
                if ("searchAlbums" in methods) acc = acc.copy(albums = callSearch("searchAlbums", argsJson).albums)
                if ("searchTracks" in methods) acc = acc.copy(tracks = callSearch("searchTracks", argsJson).tracks)
                acc
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private suspend fun callSearch(method: String, argsJson: String): SearchResults {
        val statement = "globalThis.__rizx.invokeAndCapture(${enc(uid)}, ${enc(method)}, ${enc(argsJson)})"
        val resultJson = engine.evalCaptured(statement) ?: return SearchResults()
        // A per-category method may return a bare array; wrap it under the category key so the mapper
        // (which reads {artists|albums|tracks}) can consume it uniformly.
        val normalized = when (method) {
            "searchArtists" -> """{"artists":$resultJson}"""
            "searchAlbums" -> """{"albums":$resultJson}"""
            "searchTracks" -> """{"tracks":$resultJson}"""
            else -> resultJson
        }.let { if (it.trimStart().startsWith("[") && method == "search") """{"tracks":$it}""" else it }
        return JsModelMappers.parseSearchResults(normalized, descriptorId, json)
    }

    private fun buildArgs(params: SearchParams): String = buildJsonArray {
        add(buildJsonObject {
            put("query", params.query)
            params.limit?.let { put("limit", it) }
        })
    }.toString()

    /** Encodes [s] as a JS string literal so it can be embedded safely in an eval statement. */
    private fun enc(s: String): String = json.encodeToString(String.serializer(), s)
}
