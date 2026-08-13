package fm.rizx.player.data.remote.spotify

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track

/** Registry id + [ProviderRef] helpers for Spotify (a **metadata-only** source — it never supplies audio). */
object SpotifyIds {
    const val PROVIDER = "spotify"

    fun track(id: String) = ProviderRef(PROVIDER, id)

    /** Namespaced so a playlist ref can't collide with a track ref (same convention as `DeezerIds`). */
    fun playlist(id: String) = ProviderRef(PROVIDER, "playlist:$id")

    /** Namespaced for the same reason as [playlist]. */
    fun album(id: String) = ProviderRef(PROVIDER, "album:$id")
}

private const val TRACK_URI_PREFIX = "spotify:track:"
private const val ALBUM_URI_PREFIX = "spotify:album:"

/** Matches every playlist form: web URL (incl. `/intl-es/`), `spotify:playlist:<id>`, and the embed URL. */
private val PLAYLIST_ID = Regex("""playlist[:/]([A-Za-z0-9]+)""")

/** The playlist id in [url], or null if there isn't one. Ignores `?si=…` and locale path segments. */
fun spotifyPlaylistId(url: String): String? = PLAYLIST_ID.find(url)?.groupValues?.get(1)

/** `spotify:track:<id>` → `<id>`. */
fun spotifyTrackId(uri: String?): String? =
    uri?.takeIf { it.startsWith(TRACK_URI_PREFIX) }
        ?.removePrefix(TRACK_URI_PREFIX)
        ?.takeIf { it.isNotBlank() }

/** `spotify:album:<id>` → `<id>`. */
fun spotifyAlbumId(uri: String?): String? =
    uri?.takeIf { it.startsWith(ALBUM_URI_PREFIX) }
        ?.removePrefix(ALBUM_URI_PREFIX)
        ?.takeIf { it.isNotBlank() }

/**
 * Maps an embed row to a domain [Track]. Spotify gives **metadata only** — no playable stream — so the
 * track keeps its Spotify identity and resolves to audio at play time by artist+title through the streaming
 * providers, exactly the way a Deezer track already plays via YouTube.
 *
 * [SpotifyEmbedTrackDto.subtitle] holds the artists comma-joined ("Shakira, Burna Boy"); splitting them
 * keeps the *first* artist clean, which is what the streaming search actually uses.
 */
fun SpotifyEmbedTrackDto.toTrackOrNull(): Track? {
    val trackId = spotifyTrackId(uri) ?: return null
    val name = title?.takeIf { it.isNotBlank() } ?: return null
    val artists = subtitle.orEmpty()
        .split(",")
        .mapNotNull { it.trim().takeIf(String::isNotBlank) }
        .map { ArtistCredit(name = it) }
    return Track(
        title = name,
        artists = artists,
        durationMs = duration?.takeIf { it > 0 }, // already milliseconds
        // Normally null on playlist embeds; kept so album/track embeds (which do carry art) map for free.
        artwork = coverArt.toArtworkSet(),
        source = SpotifyIds.track(trackId),
    )
}

/**
 * Maps a **pathfinder** row to a domain [Track] — the same identity and the same metadata-only contract as
 * the embed mapper above, but from a richer row: artists arrive already split into their own objects
 * (no comma-splitting heuristic, so "Tyler, The Creator" survives intact) and the album ships its cover
 * art ladder, which playlist embeds omit entirely.
 *
 * Returns null for anything that is not a playable Spotify track — podcast episodes and local files ride
 * in the same `items` array, and a row whose `uri` isn't `spotify:track:…` has no identity we can use.
 */
fun SpotifyPathfinderTrackDto.toTrackOrNull(): Track? {
    val trackId = spotifyTrackId(uri) ?: return null
    val trackName = name?.takeIf { it.isNotBlank() } ?: return null
    val cover = albumOfTrack?.coverArt.toArtworkSet()
    return Track(
        title = trackName,
        artists = artists?.items.orEmpty()
            .mapNotNull { it.profile?.name?.trim()?.takeIf(String::isNotBlank) }
            .map { ArtistCredit(name = it) },
        // An AlbumRef needs its own identity, so the album is only carried when Spotify names its uri;
        // the title alone would have to invent a ProviderRef, and invented identity is the one thing
        // ProviderRef exists to prevent.
        album = albumRefOrNull(cover),
        durationMs = trackDuration?.totalMilliseconds?.takeIf { it > 0 },
        artwork = cover,
        source = SpotifyIds.track(trackId),
    )
}

private fun SpotifyPathfinderTrackDto.albumRefOrNull(cover: ArtworkSet?): AlbumRef? {
    val album = albumOfTrack ?: return null
    val albumId = spotifyAlbumId(album.uri) ?: return null
    val title = album.name?.takeIf { it.isNotBlank() } ?: return null
    return AlbumRef(title = title, artwork = cover, source = SpotifyIds.album(albumId))
}

/**
 * Maps Spotify's image set to an [ArtworkSet], dropping blank urls. Returns null (not an empty set) when
 * there is nothing usable, so callers can treat "no cover" as a single null check.
 *
 * Every variant is tagged [ArtworkPurpose.COVER]; Spotify usually omits width/height, which simply makes
 * `pick()` fall back to the first entry.
 */
fun SpotifyCoverArtDto?.toArtworkSet(): ArtworkSet? {
    val items = this?.sources.orEmpty().mapNotNull { source ->
        val url = source.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Artwork(url = url, width = source.width, height = source.height, purpose = ArtworkPurpose.COVER)
    }
    return items.takeIf { it.isNotEmpty() }?.let { ArtworkSet(it) }
}
