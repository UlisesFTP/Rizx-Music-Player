package fm.rizx.player.data.plugin.bridge

import fm.rizx.player.domain.lossless.LosslessIndexItem
import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.Lyrics
import fm.rizx.player.domain.model.PlaylistPreview
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

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
            playlists = obj.array("playlists").mapNotNull { runCatching { toPlaylistRef(it.jsonObject, fallbackProvider) }.getOrNull() },
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

    /**
     * Rows of a community lossless index.
     *
     * Deliberately **not** routed through [parseStreamCandidates]: a `StreamCandidate` has no artist
     * field, and the artist is the single signal that keeps a same-titled recording by somebody else
     * out of the player. The three required keys are the shape every Echo-compatible index publishes;
     * the rest are read when a richer index offers them and simply stay null when it doesn't.
     */
    fun parseLosslessIndexItems(jsonStr: String, json: Json): List<LosslessIndexItem> =
        topLevelArray(jsonStr, json).mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val song = obj.str("song", "title", "name") ?: return@mapNotNull null
            val artist = obj.str("artist", "artists", "author") ?: return@mapNotNull null
            val url = obj.str("url", "downloadUrl", "stream") ?: return@mapNotNull null
            LosslessIndexItem(
                song = song,
                artist = artist,
                url = url,
                album = obj.str("album"),
                durationMs = obj["durationMs"]?.jsonPrimitive?.doubleOrNull?.toLong(),
                isrc = obj.str("isrc"),
                sha256 = obj.str("sha256", "checksum"),
                license = obj.str("license"),
                sourceName = obj.str("sourceName", "source_name"),
            )
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
        val url = sequenceOf("thumbnail", "coverImage", "image", "images", "artwork", "picture", "coverArt")
            .firstNotNullOfOrNull { key -> obj[key]?.let(::firstUrl) } ?: return null
        return ArtworkSet(listOf(Artwork(url = url, purpose = ArtworkPurpose.COVER)))
    }

    /** A URL out of whatever shape the plugin used: `"…"`, `["…", …]`, `[{url}, …]`, or `{url|src|uri}`. */
    private fun firstUrl(el: JsonElement): String? = when (el) {
        is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonArray -> el.firstOrNull()?.let(::firstUrl)
        is JsonObject -> el.str("url", "src", "uri")
        else -> null
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

    // --- detail (album / artist) -------------------------------------------

    /** Full album detail: `{title|name, artists, year?, releaseDate?, tracks: [...], …}`. */
    fun parseAlbum(jsonStr: String, fallbackProvider: String, source: ProviderRef, json: Json): Album? {
        val obj = runCatching { json.parseToJsonElement(jsonStr) as? JsonObject }.getOrNull() ?: return null
        val title = obj.str("title", "name") ?: return null
        val artists = (obj["artists"] as? JsonArray)?.mapNotNull {
            runCatching { toArtistRef(it.jsonObject, fallbackProvider) }.getOrNull()
        } ?: obj.str("artist")?.let { listOf(ArtistRef(it, source = ProviderRef(fallbackProvider, it))) } ?: emptyList()
        val releaseDate = obj.str("releaseDate", "releaseDateIso", "date")
        return Album(
            title = title,
            artists = artists,
            year = obj["year"]?.jsonPrimitive?.intOrNull ?: releaseDate?.take(4)?.toIntOrNull(),
            releaseDateIso = releaseDate,
            artwork = artworkOf(obj),
            tracks = obj.array("tracks").mapNotNull { runCatching { toTrack(it.jsonObject, fallbackProvider) }.getOrNull() },
            source = (obj["source"] as? JsonObject)?.let { toProviderRef(obj, fallbackProvider, title) } ?: source,
        )
    }

    /**
     * Assembles an [Artist] from the composed detail calls: [bioJson] (`fetchArtistBio` — object or
     * bare string), plus optional top-tracks and albums arrays. Null only when everything was empty.
     */
    fun parseArtistDetail(
        bioJson: String?,
        topTracksJson: String?,
        albumsJson: String?,
        fallbackProvider: String,
        source: ProviderRef,
        json: Json,
    ): Artist? {
        val bioObj = bioJson?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        val bioText: String?
        var name: String? = null
        var artwork: ArtworkSet? = null
        when (bioObj) {
            is JsonObject -> {
                bioText = bioObj.str("bio", "biography", "description", "summary", "text")
                name = bioObj.str("name", "title")
                artwork = artworkOf(bioObj)
            }
            is JsonPrimitive -> bioText = bioObj.contentOrNull?.takeIf { it.isNotBlank() }
            else -> bioText = null
        }
        val topTracks = topTracksJson?.let { parseTracks(it, fallbackProvider, json) }.orEmpty()
        val albums = albumsJson?.let { parseAlbumRefs(it, fallbackProvider, json) }.orEmpty()
        if (bioText == null && name == null && topTracks.isEmpty() && albums.isEmpty()) return null
        return Artist(
            name = name ?: source.id,
            bio = bioText,
            artwork = artwork ?: topTracks.firstNotNullOfOrNull { it.artwork },
            topTracks = topTracks,
            albums = albums,
            source = source,
        )
    }

    // --- lyrics / playlists -------------------------------------------------

    /** Lyrics from a plugin: a bare string, `{lyrics|plain|text}`, or `{lines: [{timeMs|time, text}]}`. */
    fun parseLyrics(jsonStr: String, sourceName: String, json: Json): Lyrics? {
        val el = runCatching { json.parseToJsonElement(jsonStr) }.getOrNull() ?: return null
        return when (el) {
            is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() }?.let { Lyrics(plain = it, sourceName = sourceName) }
            is JsonObject -> {
                val plain = el.str("lyrics", "plain", "text", "body")
                val lines = el.array("lines").mapNotNull { line ->
                    runCatching {
                        val lo = line.jsonObject
                        val time = lo["timeMs"]?.jsonPrimitive?.longOrNull ?: lo["time"]?.jsonPrimitive?.longOrNull
                        LyricLine(timeMs = time ?: return@runCatching null, text = lo.str("text") ?: "")
                    }.getOrNull()
                }
                if (plain == null && lines.isEmpty()) null
                else Lyrics(plain = plain, lines = lines, sourceName = sourceName)
            }
            else -> null
        }
    }

    /** A playlist fetched by URL: `{name|title, description?, tracks: [...], …}`. */
    fun parsePlaylistPreview(jsonStr: String, fallbackProvider: String, url: String, json: Json): PlaylistPreview? {
        val obj = runCatching { json.parseToJsonElement(jsonStr) as? JsonObject }.getOrNull() ?: return null
        val name = obj.str("name", "title") ?: return null
        return PlaylistPreview(
            name = name,
            description = obj.str("description", "subtitle"),
            tracks = obj.array("tracks").mapNotNull { runCatching { toTrack(it.jsonObject, fallbackProvider) }.getOrNull() },
            origin = toProviderRef(obj, fallbackProvider, url),
            artwork = artworkOf(obj),
        )
    }

    // --- Kotlin → JS marshalling -------------------------------------------

    /** A [ProviderRef] as the `{provider, id, url?}` object plugins round-trip. */
    fun refToJson(ref: ProviderRef): JsonObject = buildJsonObject {
        put("provider", ref.provider)
        put("id", ref.id)
        ref.url?.let { put("url", it) }
    }

    /** A [Track] in upstream plugin shape (`searchForTrackV2` / discovery context). */
    fun trackToJson(track: Track): JsonObject = buildJsonObject {
        put("title", track.title)
        put("artists", buildJsonArray {
            for (credit in track.artists) add(buildJsonObject {
                put("name", credit.name)
                credit.source?.let { put("source", refToJson(it)) }
            })
        })
        track.album?.let { put("album", buildJsonObject { put("title", it.title) }) }
        track.durationMs?.let { put("durationMs", it) }
        put("source", refToJson(track.source))
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
