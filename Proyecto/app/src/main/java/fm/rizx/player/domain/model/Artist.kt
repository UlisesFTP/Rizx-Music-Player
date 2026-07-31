package fm.rizx.player.domain.model

import kotlinx.serialization.Serializable

/**
 * A full artist — the heavier counterpart to [ArtistRef], carrying [topTracks] and [albums]. Identity
 * is [source] (a [ProviderRef]). Pure Kotlin, Android-free. Fetched on demand via
 * `MetadataProvider.artistDetail`.
 */
@Serializable
data class Artist(
    val name: String,
    val bio: String? = null,
    val artwork: ArtworkSet? = null,
    val topTracks: List<Track> = emptyList(),
    val albums: List<AlbumRef> = emptyList(),
    val followers: Long? = null,
    val source: ProviderRef,
)

/**
 * A biography and where it came from.
 *
 * [sourceUrl] is not optional decoration: the text is licensed CC BY-SA, and showing it without
 * pointing back at the article would be using it on terms it was never offered on.
 */
data class ArtistBio(val text: String, val sourceUrl: String?)

/**
 * The discography as two shelves, newest first — the way every music service presents it, and the only
 * way forty releases are readable at all.
 *
 * A release the catalogue didn't label ([AlbumKind.UNKNOWN]) is shown with the albums: that is where an
 * unlabelled record most likely belongs, and hiding it in a "singles" shelf would lose it.
 */
val Artist.albumsOnly: List<AlbumRef>
    get() = albums.filter { it.kind == AlbumKind.ALBUM || it.kind == AlbumKind.COMPILATION || it.kind == AlbumKind.UNKNOWN }
        .sortedByDescending { it.year ?: 0 }

val Artist.singlesAndEps: List<AlbumRef>
    get() = albums.filter { it.kind == AlbumKind.SINGLE || it.kind == AlbumKind.EP }
        .sortedByDescending { it.year ?: 0 }
