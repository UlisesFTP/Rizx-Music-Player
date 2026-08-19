package fm.rizx.player.data.local.store

import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.Playlist
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Versioned, self-describing playlist export envelope (Phase 15). Tracks are the full [Track] graph,
 * **resolution-stripped** so no ephemeral stream URLs are written to the file. `ignoreUnknownKeys`
 * on decode gives forward/legacy tolerance (upstream's `.passthrough()` export intent).
 */
@Serializable
data class PlaylistExport(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val name: String,
    val description: String? = null,
    val exportedAtIso: String? = null,
    /** Kept for v1 import compatibility. New v2 exports use [items]. */
    val tracks: List<Track> = emptyList(),
    val items: List<PlaylistExportItem> = emptyList(),
) {
    companion object {
        const val FORMAT = "rizx.playlist"
        const val VERSION = 2
    }
}

@Serializable
data class PlaylistExportItem(
    val track: Track,
    val note: String? = null,
    val addedAtIso: String? = null,
)

/** Pure JSON codec for [PlaylistExport]. No Android/file I/O — the UI layer supplies the bytes (SAF). */
object PlaylistTransfer {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(name: String, description: String?, tracks: List<Track>, exportedAtIso: String): String =
        json.encodeToString(
            PlaylistExport.serializer(),
            PlaylistExport(
                name = name,
                description = description,
                exportedAtIso = exportedAtIso,
                items = tracks.map { PlaylistExportItem(PortableTrackSanitizer.sanitize(it)) },
            ),
        )

    fun encode(playlist: Playlist, exportedAtIso: String): String = json.encodeToString(
        PlaylistExport.serializer(),
        PlaylistExport(
            name = playlist.name,
            description = playlist.description,
            exportedAtIso = exportedAtIso,
            items = playlist.items.map {
                PlaylistExportItem(PortableTrackSanitizer.sanitize(it.track), it.note, it.addedAtIso)
            },
        ),
    )

    /** Parses a Rizx export. Throws [IllegalArgumentException] if the payload isn't a Rizx playlist. */
    fun decode(text: String): PlaylistExport {
        val export = json.decodeFromString(PlaylistExport.serializer(), text)
        require(export.format == PlaylistExport.FORMAT) { "Not a Rizx playlist file (format='${export.format}')" }
        return export
    }

    /**
     * Parses **any** supported playlist file, trying each format in turn: Rizx's own export, a Nuclear
     * playlist, then an Exportify CSV (the common Spotify export, which carries no playlist name — hence
     * [fallbackName], normally the file name). Throws [IllegalArgumentException] when none match.
     */
    fun decodeImport(text: String, fallbackName: String? = null): ImportedPlaylist {
        if (looksLikeExportifyCsv(text)) {
            decodeExportifyCsv(text, fallbackName)?.let { return it }
        }
        runCatching { decode(text) }.getOrNull()?.let {
            val tracks = if (it.items.isNotEmpty()) it.items.map(PlaylistExportItem::track) else it.tracks
            return ImportedPlaylist(name = it.name, description = it.description, tracks = tracks)
        }
        decodeNuclearPlaylist(json, text)?.let { return it }
        throw IllegalArgumentException(
            "Unrecognized playlist file — expected a Rizx export, a Nuclear playlist, or an Exportify CSV",
        )
    }
}
