package fm.rizx.player.data.plugin.bridge

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Defensive JSON → domain mapping for values returned by JS plugins (ADR 0014). Plugin output is
 * **untrusted**: every item is mapped in a `runCatching` and dropped (not fatal) when a required field
 * is missing, so one bad row never fails the whole result. Entities carry the plugin-declared
 * `source: {provider, id, url?}` as their [ProviderRef] identity; [fallbackProvider] backs items that
 * omit it.
 */
object JsModelMappers {

    fun parseSearchResults(jsonStr: String, fallbackProvider: String, json: Json): SearchResults {
        val root = runCatching { json.parseToJsonElement(jsonStr) }.getOrNull() ?: return SearchResults()
        val obj = root as? JsonObject ?: return SearchResults()
        return SearchResults(
            artists = obj.array("artists").mapNotNull { runCatching { toArtistRef(it.jsonObject, fallbackProvider) }.getOrNull() },
            albums = obj.array("albums").mapNotNull { runCatching { toAlbumRef(it.jsonObject, fallbackProvider) }.getOrNull() },
            tracks = obj.array("tracks").mapNotNull { runCatching { toTrack(it.jsonObject, fallbackProvider) }.getOrNull() },
        )
    }

    // --- streaming ---------------------------------------------------------

    fun parseStreamCandidates(jsonStr: String, fallbackProvider: String, json: Json): List<StreamCandidate> =
        topLevelArray(jsonStr, json).mapNotNull { runCatching { toStreamCandidate(it.jsonObject, fallbackProvider) }.getOrNull() }

    fun parseStream(jsonStr: String, fallbackProvider: String, json: Json): Stream? {
        val obj = runCatching { json.parseToJsonElement(jsonStr) as? JsonObject }.getOrNull() ?: return null
        val url = obj.str("url") ?: return null
        val protocol = when (obj.str("protocol")?.lowercase()) {
            "hls" -> StreamProtocol.HLS
            "http" -> StreamProtocol.HTTP
            "file" -> StreamProtocol.FILE
            else -> StreamProtocol.HTTPS
        }
        return Stream(
            url = url,
            protocol = protocol,
            mimeType = obj.str("mimeType"),
            bitrateKbps = obj["bitrateKbps"]?.jsonPrimitive?.intOrNull,
            durationMs = obj["durationMs"]?.jsonPrimitive?.longOrNull,
            source = toProviderRef(obj, fallbackProvider, url),
        )
    }

    private fun toStreamCandidate(obj: JsonObject, fallbackProvider: String): StreamCandidate {
        val id = obj.str("id") ?: error("candidate has no id")
        return StreamCandidate(
            id = id,
            title = obj.str("title", "name") ?: id,
            durationMs = (obj["durationMs"]?.jsonPrimitive?.doubleOrNull)?.toLong(),
            thumbnail = obj.str("thumbnail", "artwork", "image"),
            source = toProviderRef(obj, fallbackProvider, id),
        )
    }

    // --- dashboard ---------------------------------------------------------

    fun parseTracks(jsonStr: String, fallbackProvider: String, json: Json): List<Track> =
        topLevelArray(jsonStr, json).mapNotNull { runCatching { toTrack(it.jsonObject, fallbackProvider) }.getOrNull() }

    fun parseArtistRefs(jsonStr: String, fallbackProvider: String, json: Json): List<ArtistRef> =
        topLevelArray(jsonStr, json).mapNotNull { runCatching { toArtistRef(it.jsonObject, fallbackProvider) }.getOrNull() }

    fun parseAlbumRefs(jsonStr: String, fallbackProvider: String, json: Json): List<AlbumRef> =
        topLevelArray(jsonStr, json).mapNotNull { runCatching { toAlbumRef(it.jsonObject, fallbackProvider) }.getOrNull() }

    fun parsePlaylistRefs(jsonStr: String, fallbackProvider: String, json: Json): List<PlaylistRef> =
        topLevelArray(jsonStr, json).mapNotNull { runCatching { toPlaylistRef(it.jsonObject, fallbackProvider) }.getOrNull() }

    private fun toPlaylistRef(obj: JsonObject, fallbackProvider: String): PlaylistRef {
        val name = obj.str("name", "title") ?: error("playlist has no name")
        val ref = toProviderRef(obj, fallbackProvider, name)
        return PlaylistRef(id = ref.id, name = name, artwork = artworkOf(obj), source = ref)
    }

    private fun topLevelArray(jsonStr: String, json: Json): List<JsonElement> =
        runCatching { (json.parseToJsonElement(jsonStr) as? JsonArray)?.jsonArray }.getOrNull() ?: emptyList()

    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray)?.jsonArray ?: emptyList()

    private fun JsonObject.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { this[it]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() } }

    private fun toProviderRef(obj: JsonObject, fallbackProvider: String, fallbackId: String): ProviderRef {
        val src = obj["source"] as? JsonObject
        val provider = src?.str("provider") ?: fallbackProvider
        val id = src?.str("id") ?: obj.str("id") ?: fallbackId
        val url = src?.str("url")
        return ProviderRef(provider, id, url)
    }

    private fun artworkOf(obj: JsonObject): ArtworkSet? {
        val url = obj.str("thumbnail", "coverImage", "image", "images", "artwork", "picture") ?: return null
        return ArtworkSet(listOf(Artwork(url = url, purpose = ArtworkPurpose.COVER)))
    }

    private fun toArtistRef(obj: JsonObject, fallbackProvider: String): ArtistRef {
        val name = obj.str("name", "title") ?: error("artist has no name")
        return ArtistRef(
            name = name,
            artwork = artworkOf(obj),
            source = toProviderRef(obj, fallbackProvider, name),
        )
    }

    private fun toAlbumRef(obj: JsonObject, fallbackProvider: String): AlbumRef {
        val title = obj.str("title", "name") ?: error("album has no title")
        val artists = (obj["artists"] as? JsonArray)?.mapNotNull {
            runCatching { toArtistRef(it.jsonObject, fallbackProvider) }.getOrNull()
        } ?: obj.str("artist")?.let { listOf(ArtistRef(it, source = ProviderRef(fallbackProvider, it))) } ?: emptyList()
        return AlbumRef(title = title, artists = artists, artwork = artworkOf(obj), source = toProviderRef(obj, fallbackProvider, title))
    }

    private fun toTrack(obj: JsonObject, fallbackProvider: String): Track {
        val title = obj.str("title", "name") ?: error("track has no title")
        val credits = (obj["artists"] as? JsonArray)?.mapNotNull { el ->
            runCatching {
                val ao = el.jsonObject
                ArtistCredit(name = ao.str("name") ?: error("no name"), source = (ao["source"] as? JsonObject)?.let { toProviderRef(ao, fallbackProvider, ao.str("name")!!) })
            }.getOrNull()
        } ?: obj.str("artist")?.let { listOf(ArtistCredit(it)) } ?: emptyList()
        val durationMs = (obj["durationMs"]?.jsonPrimitive?.doubleOrNull)?.toLong()
            ?: (obj["duration"]?.jsonPrimitive?.doubleOrNull)?.let { if (it < 10_000) (it * 1000).toLong() else it.toLong() }
        return Track(
            title = title,
            artists = credits,
            durationMs = durationMs,
            artwork = artworkOf(obj),
            source = toProviderRef(obj, fallbackProvider, title),
        )
    }
}
