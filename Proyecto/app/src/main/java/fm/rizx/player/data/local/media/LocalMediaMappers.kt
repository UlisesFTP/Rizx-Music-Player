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

    /** The `MediaStore.Audio.Media` external-primary collection, as a literal (avoids Android deps here). */
    const val AUDIO_CONTENT_URI = "content://media/external/audio/media"

    fun track(id: Long) = ProviderRef(PROVIDER, id.toString())
    fun album(id: Long) = ProviderRef(PROVIDER, "album:$id")
    fun artist(id: Long) = ProviderRef(PROVIDER, "artist:$id")

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
