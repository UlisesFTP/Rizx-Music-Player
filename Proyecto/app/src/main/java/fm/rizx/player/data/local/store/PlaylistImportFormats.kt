package fm.rizx.player.data.local.store

import fm.rizx.player.data.remote.spotify.SpotifyIds
import fm.rizx.player.data.remote.youtube.YoutubeIds
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

/**
 * Readers for the **foreign** playlist file formats we accept on import, next to Rizx's own export
 * ([PlaylistTransfer]). Only the *formats* — data layouts — are reimplemented here from their field lists;
 * no upstream code is copied (a file format isn't copyrightable, unlike an implementation).
 */

/** A playlist parsed from an imported file, whatever format it arrived in. */
data class ImportedPlaylist(
    val name: String,
    val description: String? = null,
    val tracks: List<Track> = emptyList(),
)

// ---------------------------------------------------------------- identity

/** Provider id for imported entities that carry no upstream identity of their own. */
private const val IMPORT_PROVIDER = "import"

/**
 * Deterministic identity for an imported entity with no upstream id (a Nuclear/Exportify row is often just
 * text). Hashing the metadata — rather than minting a fresh UUID — means re-importing the same list yields
 * the **same** identity, so favourites and dedup keep working across imports.
 */
internal fun importedRef(vararg parts: String?): ProviderRef {
    val key = parts.joinToString("|") { it.orEmpty().trim().lowercase() }
    val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
    return ProviderRef(IMPORT_PROVIDER, digest.joinToString("") { "%02x".format(it) }.take(16))
}

/** [AlbumRef] needs a source, so fall back to a deterministic synthetic id when there's only a name. */
private fun albumRefOrNull(title: String?, spotifyAlbumId: String? = null): AlbumRef? {
    val name = title?.takeIf { it.isNotBlank() } ?: return null
    val source = spotifyAlbumId?.let { ProviderRef(SpotifyIds.PROVIDER, it) } ?: importedRef("album", name)
    return AlbumRef(title = name, source = source)
}

private fun artworkOrNull(url: String?): ArtworkSet? =
    url?.takeIf { it.isNotBlank() }?.let { ArtworkSet(listOf(Artwork(url = it))) }

// ---------------------------------------------------------------- Nuclear JSON

@Serializable
private data class NuclearPlaylistDto(
    val name: String? = null,
    val tracks: List<NuclearTrackDto> = emptyList(),
)

@Serializable
private data class NuclearTrackDto(
    val name: String? = null,
    val title: String? = null,
    val artist: JsonElement? = null,
    val album: String? = null,
    val thumbnail: String? = null,
    val duration: JsonElement? = null,
    val stream: NuclearStreamDto? = null,
)

@Serializable
private data class NuclearStreamDto(val source: String? = null, val id: String? = null)

/**
 * Nuclear's export: `{ name, tracks: [{ artist, name, album?, thumbnail?, duration?, stream? }] }`.
 * `duration` is **seconds** and may be a number or a string; `artist` is normally a string but some
 * versions nest `{ name }`. Returns null when [text] isn't a Nuclear playlist so the caller can try the
 * next format.
 */
internal fun decodeNuclearPlaylist(json: Json, text: String): ImportedPlaylist? {
    val dto = runCatching { json.decodeFromString(NuclearPlaylistDto.serializer(), text) }.getOrNull() ?: return null
    val name = dto.name?.takeIf { it.isNotBlank() } ?: return null
    val tracks = dto.tracks.mapNotNull { it.toTrackOrNull() }
    if (tracks.isEmpty()) return null
    return ImportedPlaylist(name = name, tracks = tracks)
}

private fun NuclearTrackDto.toTrackOrNull(): Track? {
    val trackTitle = (name ?: title)?.takeIf { it.isNotBlank() } ?: return null
    val artistName = artist.artistName()
    return Track(
        title = trackTitle,
        artists = listOfNotNull(artistName?.let { ArtistCredit(name = it) }),
        album = albumRefOrNull(album),
        durationMs = duration.secondsAsMs(),
        artwork = artworkOrNull(thumbnail),
        // An already-resolved Nuclear track carries its real stream ref — keep that identity so it plays
        // that exact video; otherwise fall back to a deterministic synthetic id.
        source = stream.providerRefOrNull() ?: importedRef("track", artistName, trackTitle, album),
    )
}

private fun NuclearStreamDto?.providerRefOrNull(): ProviderRef? {
    val streamSource = this?.source?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val streamId = id?.takeIf { it.isNotBlank() } ?: return null
    // Only sources this app can actually play map to a real ref; anything else stays synthetic.
    return if (streamSource == YoutubeIds.STREAMING) ProviderRef(YoutubeIds.STREAMING, streamId) else null
}

private fun JsonElement?.artistName(): String? = when (this) {
    is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() }
    is JsonObject -> (this["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    else -> null
}

/** Nuclear durations are seconds (number or string) → ms. */
private fun JsonElement?.secondsAsMs(): Long? {
    val seconds = (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return null
    return (seconds * 1000).toLong().takeIf { it > 0 }
}

// ---------------------------------------------------------------- Exportify CSV

// Exportify (the common Spotify → file exporter) writes CSV, and its header names have drifted between
// versions, so every column is matched by a few aliases, case-insensitively.
private val CSV_TITLE = listOf("track name", "name")
private val CSV_ARTIST = listOf("artist name(s)", "artist name", "artists")
private val CSV_ALBUM = listOf("album name", "album")
private val CSV_DURATION = listOf("track duration (ms)", "duration (ms)")
private val CSV_TRACK_URI = listOf("track uri", "track id")
private val CSV_ALBUM_URI = listOf("album uri")
private val CSV_IMAGE = listOf("album image url")

/** Cheap sniff: an Exportify export always names its track column in the header row. */
internal fun looksLikeExportifyCsv(text: String): Boolean {
    val header = text.lineSequence().firstOrNull { it.isNotBlank() }?.lowercase() ?: return false
    return header.contains("track name") || header.contains("track uri")
}

/**
 * Exportify CSV → playlist. The file carries **no playlist name** (Exportify puts it in the filename), so
 * the caller supplies [fallbackName]. `Track URI` (`spotify:track:<id>`) gives real Spotify identity when
 * present; otherwise the track gets a deterministic synthetic id.
 */
internal fun decodeExportifyCsv(text: String, fallbackName: String?): ImportedPlaylist? {
    val rows = parseCsv(text)
    val header = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return null
    fun col(aliases: List<String>): Int =
        aliases.firstNotNullOfOrNull { alias -> header.indexOf(alias).takeIf { it >= 0 } } ?: -1

    val titleAt = col(CSV_TITLE).takeIf { it >= 0 } ?: return null
    val artistAt = col(CSV_ARTIST)
    val albumAt = col(CSV_ALBUM)
    val durationAt = col(CSV_DURATION)
    val trackUriAt = col(CSV_TRACK_URI)
    val albumUriAt = col(CSV_ALBUM_URI)
    val imageAt = col(CSV_IMAGE)

    fun List<String>.at(index: Int): String? =
        if (index in indices) this[index].trim().takeIf { it.isNotBlank() } else null

    val tracks = rows.drop(1).mapNotNull { row ->
        val title = row.at(titleAt) ?: return@mapNotNull null
        val artist = row.at(artistAt)
        val album = row.at(albumAt)
        val spotifyTrackId = row.at(trackUriAt)?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
        Track(
            title = title,
            // Exportify joins multiple artists with commas — split so the first stays clean for search.
            artists = artist.orEmpty().split(",").mapNotNull { it.trim().takeIf(String::isNotBlank) }
                .map { ArtistCredit(name = it) },
            album = albumRefOrNull(album, row.at(albumUriAt)?.substringAfterLast(':')),
            durationMs = row.at(durationAt)?.toLongOrNull()?.takeIf { it > 0 }, // already ms
            artwork = artworkOrNull(row.at(imageAt)),
            source = spotifyTrackId?.let { SpotifyIds.track(it) }
                ?: importedRef("track", artist, title, album),
        )
    }
    if (tracks.isEmpty()) return null
    return ImportedPlaylist(
        name = fallbackName?.takeIf { it.isNotBlank() } ?: "Imported playlist",
        tracks = tracks,
    )
}

/** Minimal RFC-4180 CSV reader: quoted fields, embedded commas/newlines, and `""` escapes. */
private fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
            c == '"' -> inQuotes = !inQuotes
            !inQuotes && c == ',' -> { row.add(field.toString()); field.clear() }
            !inQuotes && (c == '\n' || c == '\r') -> {
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                row.add(field.toString())
                field.clear()
                if (row.any { it.isNotBlank() }) rows.add(row)
                row = mutableListOf()
            }
            else -> field.append(c)
        }
        i++
    }
    row.add(field.toString())
    if (row.any { it.isNotBlank() }) rows.add(row)
    return rows
}
