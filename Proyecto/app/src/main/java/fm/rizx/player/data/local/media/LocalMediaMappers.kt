package fm.rizx.player.data.local.media

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track

/**
 * Identity + URI helpers for the on-device music library (a `MediaStore` scan, not a network provider).
 *
 * A local track's identity is its stable `MediaStore` `_ID` → `ProviderRef("local", "<id>")`. Albums and
 * artists are namespaced (`album:<id>` / `artist:<id>`) the same way [fm.rizx.player.data.remote.deezer.DeezerIds]
 * does, so a local `AlbumRef`/`ArtistRef` can't be mistaken for a track. The content URI is **never
 * persisted** — it is rebuilt from the id at play time (see `LocalLibraryRepositoryImpl.localStream`).
 */
object LocalIds {
    const val PROVIDER = "local"

    /**
     * Provider for audio opened through the system file picker (SAF). Its id **is the document URI** —
     * which is a stable name for the document (the analogue of MediaStore's `_ID`), not an ephemeral
     * resolved URL, so holding it as identity is inside the rules.
     */
    const val FILE_PROVIDER = "file"

    /** The `MediaStore.Audio.Media` external-primary collection, as a literal (avoids Android deps here). */
    const val AUDIO_CONTENT_URI = "content://media/external/audio/media"

    fun track(id: Long) = ProviderRef(PROVIDER, id.toString())
    fun album(id: Long) = ProviderRef(PROVIDER, "album:$id")
    fun artist(id: Long) = ProviderRef(PROVIDER, "artist:$id")

    fun file(uri: String) = ProviderRef(FILE_PROVIDER, uri)

    /** Strips the `album:`/`artist:` namespace to recover the raw MediaStore id. */
    fun rawId(source: ProviderRef): String = source.id.substringAfter(':')

    /** MediaStore album-art URI. Coil loads it; falls back to the tinted `CoverArt` when the art is absent. */
    fun albumArtUri(albumId: Long): String = "content://media/external/audio/albumart/$albumId"
}


/** MediaStore reports missing artist/album as this sentinel. */
private const val UNKNOWN = "<unknown>"

/**
 * Pure `MediaStore` row → domain [Track]. Deliberately free of `Cursor`/Android types so it unit-tests on
 * the plain JVM; the repository reads the columns and calls this per row. Both the track and its album
 * carry the album-art URI as artwork.
 */
fun localTrack(
    id: Long,
    title: String?,
    artist: String?,
    artistId: Long?,
    album: String?,
    albumId: Long?,
    durationMs: Long?,
    trackNumber: Int?,
    genre: String? = null,
): Track {
    val artistName = artist?.takeIf { it.isNotBlank() && !it.equals(UNKNOWN, ignoreCase = true) }
    val artwork = albumId?.takeIf { it > 0 }?.let { ArtworkSet(listOf(Artwork(url = LocalIds.albumArtUri(it)))) }
    val albumRef = album?.takeIf { it.isNotBlank() && !it.equals(UNKNOWN, ignoreCase = true) }?.let { albumTitle ->
        AlbumRef(
            title = albumTitle,
            artists = listOfNotNull(artistName?.let { ArtistRef(name = it, source = LocalIds.artist(artistId ?: 0)) }),
            artwork = artwork,
            source = LocalIds.album(albumId ?: 0),
        )
    }
    return Track(
        title = title?.takeIf { it.isNotBlank() } ?: "Unknown",
        artists = listOfNotNull(artistName?.let { ArtistCredit(name = it, source = LocalIds.artist(artistId ?: 0)) }),
        album = albumRef,
        durationMs = durationMs?.takeIf { it > 0 },
        trackNumber = trackNumber?.takeIf { it > 0 },
        artwork = artwork,
        // The file's genre tag, in the same slot a catalogue's genre would land in — so the automatic
        // equalizer reads a local song and a streamed one through exactly one field.
        tags = listOfNotNull(genre?.takeIf { it.isNotBlank() && !it.equals(UNKNOWN, ignoreCase = true) }),
        source = LocalIds.track(id),
    )
}

/**
 * A [Track] for an audio document opened through the file picker. Pure, so the fallbacks test on the JVM.
 *
 * The fallback chain is where the honesty lives: a tagged file shows its tags; an untagged one shows its
 * **file name without the extension** — which is what the user chose in the picker and how they think of
 * it — never "Unknown", which for a picked file would name nothing.
 */
fun fileTrack(
    uri: String,
    displayName: String?,
    title: String?,
    artist: String?,
    album: String?,
    durationMs: Long?,
    /** A cached copy of the embedded picture, as a `file://`-loadable absolute path. */
    artworkPath: String? = null,
): Track {
    val fallbackTitle = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
    val artwork = artworkPath?.let { ArtworkSet(listOf(Artwork(url = "file://$it"))) }
    return Track(
        title = title?.takeIf { it.isNotBlank() } ?: fallbackTitle ?: "Audio",
        artists = listOfNotNull(artist?.takeIf { it.isNotBlank() }?.let { ArtistCredit(name = it) }),
        // The album ref's id namespaces on the *name*: two picked files claiming the same album title
        // group together, which is the only grouping a bare document can offer.
        album = album?.takeIf { it.isNotBlank() }
            ?.let { AlbumRef(title = it, artwork = artwork, source = ProviderRef(LocalIds.FILE_PROVIDER, "album:$it")) },
        durationMs = durationMs?.takeIf { it > 0 },
        artwork = artwork,
        source = LocalIds.file(uri),
    )
}
