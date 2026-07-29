package fm.rizx.player.data.remote.applemusic

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import kotlinx.serialization.Serializable

/**
 * Identity namespace shared by every Apple Music source (charts RSS and the catalogue browser).
 *
 * One `provider` for all of them so the same song coming from the chart feed and from search is the
 * same `ProviderRef` — dedup across Home rows depends on it. Sub-namespaced ids (`artist:`/`album:`)
 * keep entity kinds apart within it.
 */
object AppleMusicIds {
    const val PROVIDER = "applemusic"
    const val METADATA = "applemusic-metadata"
    const val DASHBOARD = "applemusic-charts"

    fun trackRef(id: Long, url: String? = null) = ProviderRef(PROVIDER, id.toString(), url)
    fun artistRef(id: Long, url: String? = null) = ProviderRef(PROVIDER, "artist:$id", url)
    fun albumRef(id: Long, url: String? = null) = ProviderRef(PROVIDER, "album:$id", url)

    /** The numeric id inside an `artist:`/`album:` ref, or null when the ref isn't ours/that kind. */
    fun idOf(ref: ProviderRef, prefix: String): String? =
        ref.id.takeIf { ref.provider == PROVIDER && it.startsWith("$prefix:") }?.substringAfter(':')
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

/** One RSS entry — a song, an album or a playlist; the feeds share this shape. */
@Serializable
data class AppleRssSong(
    val id: String? = null,
    val name: String? = null,
    val artistName: String? = null,
    val artworkUrl100: String? = null,
    val url: String? = null,
    /** Playlist feeds name the curator here ("Apple Music Hits") instead of an artist. */
    val curatorName: String? = null,
)

/**
 * A playlist row → a [PlaylistRef] in the **catalogue** namespace (`applemusic`), never the dashboard
 * provider's id: a ref names where an item lives, and `applemusic-charts` is a source you select, not
 * a place things come from. Keeping the public `url` on the ref is what lets the owner reopen it
 * later without rebuilding a link.
 */
fun AppleRssSong.toPlaylistRefOrNull(): PlaylistRef? {
    val playlistId = id?.takeIf { it.isNotBlank() } ?: return null
    val title = name?.takeIf { it.isNotBlank() } ?: return null
    return PlaylistRef(
        id = playlistId,
        name = title,
        artwork = artworkUrl100?.takeIf { it.isNotBlank() }?.let { appleArtworkSet(it) },
        source = ProviderRef(AppleMusicIds.PROVIDER, "playlist:$playlistId", url),
    )
}

/** An album row → an [AlbumRef]. */
fun AppleRssSong.toAlbumRefOrNull(): AlbumRef? {
    val albumId = id?.takeIf { it.isNotBlank() } ?: return null
    val title = name?.takeIf { it.isNotBlank() } ?: return null
    return AlbumRef(
        title = title,
        artists = listOfNotNull(
            artistName?.takeIf { it.isNotBlank() }?.let { ArtistRef(it, source = ProviderRef(AppleMusicIds.PROVIDER, "artist:$it")) },
        ),
        artwork = artworkUrl100?.takeIf { it.isNotBlank() }?.let { appleArtworkSet(it) },
        source = ProviderRef(AppleMusicIds.PROVIDER, "album:$albumId", url),
    )
}

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
