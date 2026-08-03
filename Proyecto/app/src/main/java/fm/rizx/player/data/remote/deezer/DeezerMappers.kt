package fm.rizx.player.data.remote.deezer

import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.AlbumKind
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.MoodStation
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track

/** Deezer provider id + [ProviderRef] namespacing (tracks vs album/artist refs share the "deezer" provider). */
object DeezerIds {
    const val PROVIDER = "deezer"
    fun track(id: Long) = ProviderRef(PROVIDER, id.toString())
    fun album(id: Long) = ProviderRef(PROVIDER, "album:$id")
    fun artist(id: Long) = ProviderRef(PROVIDER, "artist:$id")
    fun playlist(id: Long) = ProviderRef(PROVIDER, "playlist:$id")
    /** Strips the `album:`/`artist:`/`playlist:` namespace prefix to recover the raw Deezer numeric id. */
    fun rawId(source: ProviderRef): String = source.id.substringAfter(':')
}

/**
 * Pure DTO → domain mappers for Deezer. Identity via [ProviderRef] (`deezer:<trackId>`,
 * `deezer:album:<id>`, `deezer:artist:<id>`). Durations seconds→ms; `release_date` "YYYY-MM-DD" → year.
 * DTOs never escape this layer (ADR 0006).
 */

/**
 * The three sizes Deezer publishes, each labelled with what it is actually good for.
 *
 * **The 500px one used to be missing, and that cost more data than anything else in the app.** `pick()`
 * only considers variants whose purpose is `null` or matches, so with the XL as the sole `COVER` a
 * `pick(COVER, 512)` had exactly one candidate: a 1000×1000 JPEG. Every tile in a 60-item Home feed was
 * fetching one — measured at 63-212 KB each against Deezer today, where the 500px version of the same
 * cover is 22-87 KB and looks identical at tile size.
 *
 * Both the 500 and the 1000 are `COVER`, and the **requested size** picks between them — which is what
 * `pick()` was written to do and could not, having been given one option. A tile asking for 512 gets the
 * 500 (a 12px difference); a full-screen hero asking for 1080 gets the 1000, because upscaling the 500
 * by 2.16× trips the scorer's penalty. The 250 stays a `THUMBNAIL`, which is the rung data saving drops to.
 */
private fun coverSet(xl: String?, big: String?, medium: String?): ArtworkSet? {
    val variants = buildList {
        big?.takeIf { it.isNotBlank() }?.let { add(Artwork(it, 500, 500, ArtworkPurpose.COVER)) }
        xl?.takeIf { it.isNotBlank() }?.let { add(Artwork(it, 1000, 1000, ArtworkPurpose.COVER)) }
        medium?.takeIf { it.isNotBlank() }?.let { add(Artwork(it, 250, 250, ArtworkPurpose.THUMBNAIL)) }
    }
    return variants.takeIf { it.isNotEmpty() }?.let { ArtworkSet(it) }
}

private fun yearOf(releaseDate: String?): Int? = releaseDate?.take(4)?.toIntOrNull()

fun DeezerArtistShortDto.toArtistRef(): ArtistRef? {
    val n = name ?: return null
    return ArtistRef(
        name = n,
        artwork = coverSet(pictureXl, pictureBig, pictureMedium),
        source = DeezerIds.artist(id ?: 0),
        followers = nbFan,
    )
}

fun DeezerAlbumShortDto.toAlbumRef(): AlbumRef? {
    val t = title ?: return null
    return AlbumRef(
        title = t,
        artists = listOfNotNull(artist?.toArtistRef()),
        artwork = coverSet(coverXl, coverBig, coverMedium),
        source = DeezerIds.album(id ?: 0),
        year = yearOf(releaseDate),
        kind = AlbumKind.of(recordType),
    )
}

fun DeezerPlaylistDto.toPlaylistRef(): PlaylistRef? {
    val playlistId = id ?: return null
    val t = title ?: return null
    return PlaylistRef(
        id = playlistId.toString(),
        name = t,
        artwork = coverSet(pictureXl, pictureBig, pictureMedium),
        source = DeezerIds.playlist(playlistId),
        trackCount = nbTracks,
    )
}

/** Titles arrive with stray whitespace ("80's ", verified live) — trimmed so dedupe-by-title works. */
fun DeezerRadioDto.toMoodStation(): MoodStation? {
    val stationId = id ?: return null
    val t = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return MoodStation(
        id = stationId.toString(),
        title = t,
        artworkUrl = pictureMedium?.takeIf { it.isNotBlank() },
    )
}

fun DeezerTrackDto.toTrackOrNull(): Track? {
    val trackId = id ?: return null
    val name = title ?: return null
    val artistCredit = artist?.name?.let {
        ArtistCredit(name = it, source = artist.id?.let { a -> DeezerIds.artist(a) })
    }
    return Track(
        title = name,
        artists = listOfNotNull(artistCredit),
        album = album?.toAlbumRef(),
        durationMs = duration?.let { it.toLong() * 1000 },
        trackNumber = trackPosition,
        artwork = album?.let { coverSet(it.coverXl, it.coverBig, it.coverMedium) },
        source = DeezerIds.track(trackId),
    )
}

fun DeezerAlbumDto.toAlbum(): Album? {
    val albumId = id ?: return null
    val name = title ?: return null
    return Album(
        title = name,
        artists = listOfNotNull(artist?.toArtistRef()),
        year = yearOf(releaseDate),
        // Deezer already reports "YYYY-MM-DD"; keep it whole so a tag can carry the exact date.
        releaseDateIso = releaseDate?.takeIf { it.isNotBlank() },
        artwork = coverSet(coverXl, coverBig, coverMedium),
        tracks = tracks?.data?.mapNotNull { it.toTrackOrNull() }.orEmpty(),
        totalTracks = nbTracks,
        durationMs = duration?.let { it.toLong() * 1000 },
        // Genre first, blanks dropped: this is the automatic equalizer's cheapest source of a family,
        // and Deezer reports genres here and nowhere else.
        tags = genres?.data?.mapNotNull { it.name?.takeIf(String::isNotBlank) }.orEmpty(),
        source = DeezerIds.album(albumId),
    )
}

fun DeezerArtistDto.toArtist(topTracks: List<Track>, albums: List<AlbumRef>): Artist? {
    val artistId = id ?: return null
    val n = name ?: return null
    return Artist(
        name = n,
        artwork = coverSet(pictureXl, pictureBig, pictureMedium),
        topTracks = topTracks,
        albums = albums,
        followers = nbFan,
        source = DeezerIds.artist(artistId),
    )
}

/** Assembles grouped [SearchResults] from Deezer track rows, deriving distinct artist/album refs. */
fun List<DeezerTrackDto>.toSearchResults(): SearchResults {
    val tracks = mapNotNull { it.toTrackOrNull() }
    val artists = mapNotNull { it.artist }.distinctBy { it.id ?: it.name }.mapNotNull { it.toArtistRef() }
    val albums = mapNotNull { it.album }.distinctBy { it.id ?: it.title }.mapNotNull { it.toAlbumRef() }
    return SearchResults(artists = artists, albums = albums, tracks = tracks)
}
