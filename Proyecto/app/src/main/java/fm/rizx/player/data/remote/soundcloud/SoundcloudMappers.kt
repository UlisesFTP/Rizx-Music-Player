package fm.rizx.player.data.remote.soundcloud

import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.data.remote.youtube.bestThumbnailUrl
import fm.rizx.player.data.remote.youtube.toArtworkSet
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.Track
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Registry ids for the native SoundCloud providers. */
object SoundcloudIds {
    const val STREAMING = "soundcloud"
    const val METADATA = "soundcloud-metadata"
    const val DASHBOARD = "soundcloud-charts"

    /** NewPipe kiosk ids for SoundCloud's chart pages. */
    const val KIOSK_TOP_50 = "Top 50"
    const val KIOSK_NEW_HOT = "New & hot"
}

/**
 * SoundCloud DTO → domain mappers.
 *
 * **Identity is the track URL, not a short id.** SoundCloud has no 11-char video id like YouTube; NewPipe
 * resolves a track by its permalink URL, so the URL is both the [ProviderRef.id] and its [ProviderRef.url].
 * Two different tracks have different permalinks, so this is a stable identity.
 */
private fun soundcloudRef(url: String) = ProviderRef(SoundcloudIds.STREAMING, url, url)

/** A search row → a playable [Track] for the Search "Underground" tab. Drops rows with no url/title. */
fun StreamInfoItem.toSoundcloudTrackOrNull(): Track? {
    val trackUrl = url?.takeIf { it.isNotBlank() } ?: return null
    val name = name?.takeIf { it.isNotBlank() } ?: return null
    return Track(
        title = name,
        artists = listOfNotNull(uploaderName?.takeIf { it.isNotBlank() }?.let { ArtistCredit(name = it) }),
        durationMs = duration.takeIf { it > 0 }?.let { it * 1000L },
        artwork = thumbnails.toArtworkSet(),
        source = soundcloudRef(trackUrl),
    )
}

/**
 * A SoundCloud user row → an [ArtistRef] for the Search "Artists" tab.
 *
 * Identity is the profile permalink for the same reason tracks use theirs: SoundCloud exposes no stable
 * short id through NewPipe's search rows.
 */
fun ChannelInfoItem.toSoundcloudArtistOrNull(): ArtistRef? {
    val profileUrl = url?.takeIf { it.isNotBlank() } ?: return null
    val artistName = name?.takeIf { it.isNotBlank() } ?: return null
    return ArtistRef(
        name = artistName,
        artwork = thumbnails.toArtworkSet(),
        source = ProviderRef(SoundcloudIds.METADATA, profileUrl, profileUrl),
    )
}

/** Phase-1 candidate from a search row (fallback resolution by artist/title). */
fun StreamInfoItem.toSoundcloudCandidateOrNull(): StreamCandidate? {
    val trackUrl = url?.takeIf { it.isNotBlank() } ?: return null
    val name = name?.takeIf { it.isNotBlank() } ?: return null
    return StreamCandidate(
        id = trackUrl,
        title = name,
        durationMs = duration.takeIf { it > 0 }?.let { it * 1000L },
        thumbnail = thumbnails.bestThumbnailUrl(),
        source = soundcloudRef(trackUrl),
    )
}

/**
 * A phase-1 candidate built straight from a track that **already is a SoundCloud track** (tapped in the
 * Underground tab): resolves that exact permalink instead of re-searching by artist/title. Null otherwise.
 * Mirrors `Track.toYoutubeCandidateOrNull`.
 */
fun Track.toSoundcloudCandidateOrNull(): StreamCandidate? {
    if (source.provider != SoundcloudIds.STREAMING) return null
    val trackUrl = source.url ?: source.id
    return StreamCandidate(
        id = source.id,
        title = title,
        durationMs = durationMs,
        source = ProviderRef(SoundcloudIds.STREAMING, source.id, trackUrl),
    )
}
