package fm.rizx.player.data.plugin.bridge

import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.DetailCapability
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.model.SearchCategory
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bridges a JS-registered metadata provider descriptor into the Kotlin [MetadataProvider] contract
 * (ADR 0014/0019). Each call marshals arguments to JSON, invokes the plugin's async method through the
 * [QuickJsEngine], and maps the JSON result back defensively via [JsModelMappers]. Failures surface as
 * [fm.rizx.player.data.plugin.engine.PluginException] (a plain Exception) so the repository/resolver
 * treat a broken plugin as an isolated failure, never a crash.
 *
 * Beyond search this now covers the real Nuclear surface: per-category searches honoring
 * [SearchParams.types], album/artist detail composed from the plugin's `fetchAlbumDetails` /
 * `fetchArtistBio`+`fetchArtistTopTracks`+`fetchArtistAlbums`, and related-artist radio when the
 * plugin exposes the pieces.
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
    private val invoker: JsProviderInvoker,
    private val json: Json,
) : MetadataProvider {

    override val kind: ProviderKind = ProviderKind.METADATA

    override val detailCapabilities: Set<DetailCapability> = buildSet {
        if ("fetchAlbumDetails" in methods) add(DetailCapability.ALBUM_DETAIL)
        if (ARTIST_DETAIL_METHODS.any { it in methods }) add(DetailCapability.ARTIST_DETAIL)
    }

    override suspend fun search(params: SearchParams): SearchResults {
        val argsJson = buildArgs(params)
        val types = params.types
        return try {
            if (types == null && (SearchCapability.UNIFIED in searchCapabilities || "search" in methods) && "search" in methods) {
                callSearch("search", argsJson)
            } else {
                // Merge per-category searches (e.g. Discogs exposes searchArtists/searchAlbums),
                // honoring the requested categories the way the native providers do.
                fun wanted(cat: SearchCategory) = types == null || cat in types
                var acc = SearchResults()
                if ("searchArtists" in methods && wanted(SearchCategory.ARTISTS)) {
                    acc = acc.copy(artists = callSearch("searchArtists", argsJson).artists)
                }
                if ("searchAlbums" in methods && wanted(SearchCategory.ALBUMS)) {
                    acc = acc.copy(albums = callSearch("searchAlbums", argsJson).albums)
                }
                if ("searchTracks" in methods && wanted(SearchCategory.TRACKS)) {
                    acc = acc.copy(tracks = callSearch("searchTracks", argsJson).tracks)
                }
                if ("searchPlaylists" in methods && types != null && SearchCategory.PLAYLISTS in types) {
                    acc = acc.copy(playlists = callSearch("searchPlaylists", argsJson).playlists)
                }
                // A unified-only plugin still answers a typed query — filter its unified result.
                if (acc.isEmpty && "search" in methods) {
                    val unified = callSearch("search", argsJson)
                    acc = SearchResults(
                        artists = if (wanted(SearchCategory.ARTISTS)) unified.artists else emptyList(),
                        albums = if (wanted(SearchCategory.ALBUMS)) unified.albums else emptyList(),
                        tracks = if (wanted(SearchCategory.TRACKS)) unified.tracks else emptyList(),
                        playlists = if (types != null && SearchCategory.PLAYLISTS in types) unified.playlists else emptyList(),
                    )
                }
                acc
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    override suspend fun albumDetail(source: ProviderRef): Album? {
        if ("fetchAlbumDetails" !in methods) return null
        val result = invoke("fetchAlbumDetails", refArgs(source)) ?: return null
        return JsModelMappers.parseAlbum(result, descriptorId, source, json)
    }

    override suspend fun artistDetail(source: ProviderRef): Artist? {
        if (DetailCapability.ARTIST_DETAIL !in detailCapabilities) return null
        val args = refArgs(source)
        val bio = if ("fetchArtistBio" in methods) runCatching { invoke("fetchArtistBio", args) }.getOrNull() else null
        val top = if ("fetchArtistTopTracks" in methods) runCatching { invoke("fetchArtistTopTracks", args) }.getOrNull() else null
        val albums = if ("fetchArtistAlbums" in methods) runCatching { invoke("fetchArtistAlbums", args) }.getOrNull() else null
        return JsModelMappers.parseArtistDetail(bio, top, albums, descriptorId, source, json)
    }

    /**
     * Related-artist radio, only when the plugin has both pieces **and** the seed's artist credit
     * carries this provider's own ref (i.e. the seed came from this catalog — otherwise there is no id
     * to ask about). Failures and gaps mean empty; callers fall back.
     */
    override suspend fun radioTracks(seed: Track): List<Track> {
        if ("fetchArtistRelatedArtists" !in methods || "fetchArtistTopTracks" !in methods) return emptyList()
        val artistRef = seed.artists.firstNotNullOfOrNull { it.source?.takeIf { s -> s.provider == descriptorId } }
            ?: return emptyList()
        val related = invoke("fetchArtistRelatedArtists", refArgs(artistRef)) ?: return emptyList()
        val relatedRefs = JsModelMappers.parseArtistRefs(related, descriptorId, json).take(RADIO_RELATED_ARTISTS)
        val tracks = mutableListOf<Track>()
        for (ref in relatedRefs) {
            val top = runCatching { invoke("fetchArtistTopTracks", refArgs(ref.source)) }.getOrNull() ?: continue
            tracks += JsModelMappers.parseTracks(top, descriptorId, json).take(RADIO_TRACKS_PER_ARTIST)
        }
        return tracks.distinctBy { it.source }
    }

    private suspend fun callSearch(method: String, argsJson: String): SearchResults {
        val resultJson = invoke(method, argsJson) ?: return SearchResults()
        // A per-category method may return a bare array; wrap it under the category key so the mapper
        // (which reads {artists|albums|tracks|playlists}) can consume it uniformly.
        val normalized = when (method) {
            "searchArtists" -> """{"artists":$resultJson}"""
            "searchAlbums" -> """{"albums":$resultJson}"""
            "searchTracks" -> """{"tracks":$resultJson}"""
            "searchPlaylists" -> """{"playlists":$resultJson}"""
            else -> resultJson
        }.let { if (it.trimStart().startsWith("[") && method == "search") """{"tracks":$it}""" else it }
        return JsModelMappers.parseSearchResults(normalized, descriptorId, json)
    }

    private suspend fun invoke(method: String, argsJson: String): String? =
        invoker.invoke(uid, method, argsJson)

    private fun buildArgs(params: SearchParams): String = buildJsonArray {
        add(buildJsonObject {
            put("query", params.query)
            params.limit?.let { put("limit", it) }
        })
    }.toString()

    /** Detail calls receive the `{provider, id, url?}` ref the plugin originally emitted. */
    private fun refArgs(source: ProviderRef): String =
        buildJsonArray { add(JsModelMappers.refToJson(source)) }.toString()

    private companion object {
        val ARTIST_DETAIL_METHODS = setOf("fetchArtistBio", "fetchArtistTopTracks", "fetchArtistAlbums")
        const val RADIO_RELATED_ARTISTS = 5
        const val RADIO_TRACKS_PER_ARTIST = 5
    }
}
