package fm.rizx.player.data.remote.spotify

import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track

/** Registry id + [ProviderRef] helpers for Spotify (a **metadata-only** source — it never supplies audio). */
object SpotifyIds {
    const val PROVIDER = "spotify"

    fun track(id: String) = ProviderRef(PROVIDER, id)

    /** Namespaced so a playlist ref can't collide with a track ref (same convention as `DeezerIds`). */
    fun playlist(id: String) = ProviderRef(PROVIDER, "playlist:$id")
}

private const val TRACK_URI_PREFIX = "spotify:track:"

/** Matches every playlist form: web URL (incl. `/intl-es/`), `spotify:playlist:<id>`, and the embed URL. */
private val PLAYLIST_ID = Regex("""playlist[:/]([A-Za-z0-9]+)""")

/** The playlist id in [url], or null if there isn't one. Ignores `?si=…` and locale path segments. */
fun spotifyPlaylistId(url: String): String? = PLAYLIST_ID.find(url)?.groupValues?.get(1)

/** `spotify:track:<id>` → `<id>`. */
fun spotifyTrackId(uri: String?): String? =
    uri?.takeIf { it.startsWith(TRACK_URI_PREFIX) }
        ?.removePrefix(TRACK_URI_PREFIX)
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
        source = SpotifyIds.track(trackId),
    )
}
