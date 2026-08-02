package fm.rizx.player.data.remote.itunes

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track

/** Registry ids for the iTunes providers. Kept together so mappers and providers agree on identity. */
object ItunesIds {
    const val METADATA = "itunes-metadata"
    const val STREAMING = "itunes-streaming"
}

/**
 * Pure DTO → domain mappers for the iTunes provider. All identity flows through [ProviderRef]
 * (`itunes:<trackId>` for tracks; `itunes:artist:<id>` / `itunes:album:<id>` for refs). Results with
 * no `trackId`/`trackName` are dropped. No resolved stream URL is ever attached here — streams are
 * resolved just-in-time by the streaming provider. DTOs never escape this layer (ADR 0006).
 */

/** Upsizes the 100×100 thumbnail to a 600×600 cover by rewriting iTunes' fixed size token. */
private fun ItunesResultDto.artworkSet(): ArtworkSet? {
    val small = artworkUrl100 ?: artworkUrl60 ?: return null
    val large = small.replace("100x100bb", "1000x1000bb").replace("60x60bb", "1000x1000bb")
    val variants = buildList {
        if (large != small) add(Artwork(url = large, width = 1000, height = 1000, purpose = ArtworkPurpose.COVER))
        add(Artwork(url = small, width = 100, height = 100, purpose = ArtworkPurpose.THUMBNAIL))
    }
    return ArtworkSet(variants)
}

private fun ItunesResultDto.artistRef(): ArtistRef? {
    val name = artistName ?: return null
    val id = artistId?.let { "artist:$it" } ?: "artist:$name"
    return ArtistRef(name = name, source = ProviderRef("itunes", id, url = artistViewUrl))
}

/** Maps a `song` result to a full, playable-by-resolution [Track], or `null` if it lacks identity. */
fun ItunesResultDto.toTrackOrNull(): Track? {
    val id = trackId ?: return null
    val title = trackName ?: return null
    val artist = artistName?.let {
        ArtistCredit(name = it, source = artistId?.let { a -> ProviderRef("itunes", "artist:$a") })
    }
    val album = collectionName?.let {
        val albumId = collectionId?.let { c -> "album:$c" } ?: "album:$it"
        AlbumRef(
            title = it,
            artists = listOfNotNull(artistRef()),
            artwork = artworkSet(),
            source = ProviderRef("itunes", albumId, url = collectionViewUrl),
        )
    }
    return Track(
        title = title,
        artists = listOfNotNull(artist),
        album = album,
        durationMs = trackTimeMillis,
        trackNumber = trackNumber,
        disc = discNumber?.toString(),
        artwork = artworkSet(),
        tags = listOfNotNull(primaryGenreName),
        source = ProviderRef("itunes", id.toString(), url = trackViewUrl),
    )
}

/** Assembles grouped [SearchResults] from song rows, deriving distinct artist/album refs. */
fun List<ItunesResultDto>.toSearchResults(): SearchResults {
    val tracks = mapNotNull { it.toTrackOrNull() }
    val artists = filter { it.artistName != null }
        .distinctBy { it.artistId ?: it.artistName }
        .mapNotNull { it.artistRef() }
    val albums = filter { it.collectionName != null }
        .distinctBy { it.collectionId ?: it.collectionName }
        .mapNotNull { dto ->
            dto.collectionName?.let { name ->
                val albumId = dto.collectionId?.let { "album:$it" } ?: "album:$name"
                AlbumRef(
                    title = name,
                    artists = listOfNotNull(dto.artistRef()),
                    artwork = dto.let { r -> r.toTrackOrNull()?.artwork },
                    source = ProviderRef("itunes", albumId, url = dto.collectionViewUrl),
                )
            }
        }
    return SearchResults(artists = artists, albums = albums, tracks = tracks)
}

/** Phase-1 candidate: identity only (id = iTunes trackId); no stream URL until [toStream]. */
fun ItunesResultDto.toStreamCandidateOrNull(): StreamCandidate? {
    val id = trackId ?: return null
    val title = trackName ?: return null
    return StreamCandidate(
        id = id.toString(),
        title = title,
        durationMs = trackTimeMillis,
        thumbnail = artworkUrl100 ?: artworkUrl60,
        source = ProviderRef(ItunesIds.STREAMING, id.toString()),
    )
}

/** Phase-2 concrete stream from the preview URL, or `null` when iTunes offers no preview. */
fun ItunesResultDto.toStream(candidateId: String): Stream? {
    val url = previewUrl ?: return null
    return Stream(
        url = url,
        protocol = StreamProtocol.HTTPS,
        mimeType = "audio/mp4",
        codec = "aac",
        container = "m4a",
        qualityLabel = "Preview (30s)",
        durationMs = trackTimeMillis,
        source = ProviderRef(ItunesIds.STREAMING, candidateId),
    )
}
