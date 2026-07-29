package fm.rizx.player.data.remote.applemusic

import fm.rizx.player.data.remote.itunes.ItunesResultDto
import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track

// Maps iTunes Search rows into the **Apple Music** provider's domain shapes (ADR 0018).
//
// The Apple Music catalogue browser and the existing `itunes-*` provider share this wire DTO but not
// their identity namespace: `itunes-streaming` is a 30-second-preview source, while these rows are the
// full catalogue the user can pick as their metadata source. Refs are `applemusic:<trackId>`,
// `applemusic:artist:<id>` and `applemusic:album:<id>` (see AppleMusicIds).

private fun ItunesResultDto.artworkSet(): ArtworkSet? {
    val small = artworkUrl100 ?: artworkUrl60 ?: return null
    val large = small.replace("100x100bb", "600x600bb").replace("60x60bb", "600x600bb")
    return ArtworkSet(
        buildList {
            if (large != small) add(Artwork(url = large, width = 600, height = 600, purpose = ArtworkPurpose.COVER))
            add(Artwork(url = small, width = 100, height = 100, purpose = ArtworkPurpose.THUMBNAIL))
        },
    )
}

private fun ItunesResultDto.appleArtistRef(): ArtistRef? {
    val name = artistName ?: collectionArtistName ?: return null
    val ref = artistId?.let { AppleMusicIds.artistRef(it, artistViewUrl) }
        ?: ProviderRef(AppleMusicIds.PROVIDER, "artist:$name")
    return ArtistRef(name = name, source = ref)
}

/** A `song` row → a playable [Track] (audio resolves through the normal streaming chain). */
fun ItunesResultDto.toAppleTrackOrNull(): Track? {
    val id = trackId ?: return null
    val title = trackName ?: return null
    val credit = artistName?.let {
        ArtistCredit(name = it, source = artistId?.let { a -> AppleMusicIds.artistRef(a) })
    }
    val album = collectionName?.let { name ->
        AlbumRef(
            title = name,
            artists = listOfNotNull(appleArtistRef()),
            artwork = artworkSet(),
            source = collectionId?.let { AppleMusicIds.albumRef(it, collectionViewUrl) }
                ?: ProviderRef(AppleMusicIds.PROVIDER, "album:$name"),
        )
    }
    return Track(
        title = title,
        artists = listOfNotNull(credit),
        album = album,
        durationMs = trackTimeMillis,
        trackNumber = trackNumber,
        disc = discNumber?.toString(),
        artwork = artworkSet(),
        tags = listOfNotNull(primaryGenreName),
        source = AppleMusicIds.trackRef(id, trackViewUrl),
    )
}

/** A `musicArtist` row → an [ArtistRef]. Artist rows carry no artwork on this API. */
fun ItunesResultDto.toAppleArtistOrNull(): ArtistRef? {
    val id = artistId ?: return null
    val name = artistName ?: return null
    return ArtistRef(name = name, source = AppleMusicIds.artistRef(id, artistViewUrl))
}

/** A `collection` (album) row → an [AlbumRef]. */
fun ItunesResultDto.toAppleAlbumRefOrNull(): AlbumRef? {
    val id = collectionId ?: return null
    val title = collectionName ?: return null
    return AlbumRef(
        title = title,
        artists = listOfNotNull(appleArtistRef()),
        artwork = artworkSet(),
        source = AppleMusicIds.albumRef(id, collectionViewUrl),
    )
}

/** An album `lookup` (first row = the album, rest = its songs) → full [Album] detail. */
fun List<ItunesResultDto>.toAppleAlbum(source: ProviderRef): Album? {
    val head = firstOrNull { it.collectionId != null } ?: return null
    val title = head.collectionName ?: return null
    return Album(
        title = title,
        artists = listOfNotNull(head.appleArtistRef()),
        year = head.releaseDate?.take(4)?.toIntOrNull(),
        releaseDateIso = head.releaseDate?.take(10),
        artwork = head.artworkSet(),
        tracks = filter { it.trackId != null }.mapNotNull { it.toAppleTrackOrNull() },
        totalTracks = head.trackCount,
        source = source,
    )
}

/** Composes artist detail from a song lookup (top tracks) and an album lookup. */
fun appleArtistDetail(
    source: ProviderRef,
    songRows: List<ItunesResultDto>,
    albumRows: List<ItunesResultDto>,
): Artist? {
    val name = songRows.firstNotNullOfOrNull { it.artistName }
        ?: albumRows.firstNotNullOfOrNull { it.artistName ?: it.collectionArtistName }
        ?: return null
    val topTracks = songRows.mapNotNull { it.toAppleTrackOrNull() }
    val albums = albumRows.mapNotNull { it.toAppleAlbumRefOrNull() }
    if (topTracks.isEmpty() && albums.isEmpty()) return null
    return Artist(
        name = name,
        artwork = topTracks.firstNotNullOfOrNull { it.artwork } ?: albums.firstNotNullOfOrNull { it.artwork },
        topTracks = topTracks,
        albums = albums,
        source = source,
    )
}

/** Grouped results for the unified search: songs plus the artists/albums they imply. */
fun List<ItunesResultDto>.toAppleSearchResults(): SearchResults = SearchResults(
    artists = filter { it.artistId != null }.distinctBy { it.artistId }.mapNotNull { it.appleArtistRef() },
    albums = filter { it.collectionId != null }.distinctBy { it.collectionId }.mapNotNull { it.toAppleAlbumRefOrNull() },
    tracks = mapNotNull { it.toAppleTrackOrNull() },
)
