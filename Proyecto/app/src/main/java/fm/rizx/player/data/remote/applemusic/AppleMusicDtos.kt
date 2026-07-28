package fm.rizx.player.data.remote.applemusic

import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import kotlinx.serialization.Serializable

/** Registry/identity id for the Apple Music charts source. */
object AppleMusicIds {
    const val PROVIDER = "applemusic"
}

/** Envelope of every marketing-tools RSS feed: `{ "feed": { …, "results": […] } }`. */
@Serializable
data class AppleRssResponse(val feed: AppleRssFeed = AppleRssFeed())

@Serializable
data class AppleRssFeed(
    val title: String? = null,
    val country: String? = null,
    val results: List<AppleRssSong> = emptyList(),
)

/** One most-played song entry. */
@Serializable
data class AppleRssSong(
    val id: String? = null,
    val name: String? = null,
    val artistName: String? = null,
    val artworkUrl100: String? = null,
    val url: String? = null,
)

/**
 * RSS song → domain [Track]. Identity is Apple's catalog id; playback is provider-agnostic — the
 * streaming chain resolves by artist+title at play time, exactly like a Deezer or Spotify track.
 */
fun AppleRssSong.toTrackOrNull(): Track? {
    val catalogId = id?.takeIf { it.isNotBlank() } ?: return null
    val title = name?.takeIf { it.isNotBlank() } ?: return null
    return Track(
        title = title,
        artists = listOfNotNull(artistName?.takeIf { it.isNotBlank() }?.let { ArtistCredit(name = it) }),
        artwork = artworkUrl100?.takeIf { it.isNotBlank() }?.let { appleArtworkSet(it) },
        source = ProviderRef(AppleMusicIds.PROVIDER, catalogId, url),
    )
}

/**
 * The RSS thumb is 100×100; the same asset serves larger renditions by path substitution.
 *
 * [COVER_PX] rather than the 400 this used to ask for: a chart song is one tap from filling the whole
 * Now Playing screen, and a 400 px image stretched over a ~1080 px phone is visibly soft. Apple's CDN
 * serves this size from the same path, so it costs nothing but bytes on the tiles that need it.
 */
internal fun appleArtworkSet(artworkUrl100: String): ArtworkSet = ArtworkSet(
    listOfNotNull(
        upscaleAppleArtwork(artworkUrl100, COVER_PX)
            .let { Artwork(url = it, width = COVER_PX, height = COVER_PX, purpose = ArtworkPurpose.COVER) },
        // Kept as a distinct variant so a small tile can pick the cheap one instead of decoding 1000 px.
        Artwork(url = artworkUrl100, width = THUMB_PX, height = THUMB_PX, purpose = ArtworkPurpose.THUMBNAIL),
    ),
)

internal fun upscaleAppleArtwork(url: String, px: Int = COVER_PX): String =
    url.replace("${THUMB_PX}x$THUMB_PX", "${px}x$px")

private const val THUMB_PX = 100
private const val COVER_PX = 1000
