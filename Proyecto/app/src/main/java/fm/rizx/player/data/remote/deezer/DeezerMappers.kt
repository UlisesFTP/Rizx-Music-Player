package fm.rizx.player.data.remote.deezer

import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkSet
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

private fun coverSet(xl: String?, medium: String?): ArtworkSet? {
    val variants = buildList {
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
        artwork = coverSet(pictureXl, pictureMedium),
        source = DeezerIds.artist(id ?: 0),
        followers = nbFan,
    )
}

fun DeezerAlbumShortDto.toAlbumRef(): AlbumRef? {
    val t = title ?: return null
    return AlbumRef(
        title = t,
        artists = listOfNotNull(artist?.toArtistRef()),
        artwork = coverSet(coverXl, coverMedium),
        source = DeezerIds.album(id ?: 0),
    )
}

fun DeezerPlaylistDto.toPlaylistRef(): PlaylistRef? {
    val playlistId = id ?: return null
    val t = title ?: return null
    return PlaylistRef(
        id = playlistId.toString(),
        name = t,
        artwork = coverSet(pictureXl, pictureMedium),
        source = DeezerIds.playlist(playlistId),
        trackCount = nbTracks,
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
        artwork = album?.let { coverSet(it.coverXl, it.coverMedium) },
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
        artwork = coverSet(coverXl, coverMedium),
        tracks = tracks?.data?.mapNotNull { it.toTrackOrNull() }.orEmpty(),
        totalTracks = nbTracks,
        durationMs = duration?.let { it.toLong() * 1000 },
        source = DeezerIds.album(albumId),
    )
}

fun DeezerArtistDto.toArtist(topTracks: List<Track>, albums: List<AlbumRef>): Artist? {
    val artistId = id ?: return null
    val n = name ?: return null
    return Artist(
        name = n,
        artwork = coverSet(pictureXl, pictureMedium),
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
