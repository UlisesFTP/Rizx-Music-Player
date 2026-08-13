package fm.rizx.player.data.remote.spotify

import kotlinx.serialization.Serializable

/**
 * DTOs for Spotify's **pathfinder** GraphQL gateway (`api-partner.spotify.com/pathfinder/v1/query`),
 * the endpoint the web player itself uses to page a playlist beyond the 100 rows its embed ships.
 *
 * Still **keyless** in the project's sense (ADR 0018, "keyless means public, not merely reachable"): the
 * bearer is the *anonymous* token the embed page already publishes in plain sight inside `__NEXT_DATA__`
 * (`settings.session.accessToken`, `isAnonymous: true`), and the persisted-query hash is a constant read
 * out of the public web-player bundle. Nothing here defeats an access control — verified live, the
 * gateway answers a plain request with an honest User-Agent and no browser-only headers. That is exactly
 * the line ADR 0018 draws: Spotify's *search* stays absent because `get_access_token` demands an
 * XOR-obfuscated TOTP added specifically to block non-browser clients; this path is asked for nothing.
 *
 * Shape: `data.playlistV2.content` → `{ totalCount, items[].itemV2.data }`. Only the mapped fields are
 * declared; the shared `Json` ignores the rest.
 */
@Serializable
data class SpotifyPathfinderResponse(val data: SpotifyPathfinderData? = null)

@Serializable
data class SpotifyPathfinderData(val playlistV2: SpotifyPathfinderPlaylist? = null)

@Serializable
data class SpotifyPathfinderPlaylist(val content: SpotifyPathfinderContent? = null)

/**
 * One page. [totalCount] is the playlist's real length — the walk pages until it is reached, so a
 * 1,200-track playlist imports whole instead of stopping at whatever one response happened to hold.
 */
@Serializable
data class SpotifyPathfinderContent(
    val totalCount: Int? = null,
    val items: List<SpotifyPathfinderItem> = emptyList(),
)

@Serializable
data class SpotifyPathfinderItem(val itemV2: SpotifyPathfinderItemV2? = null)

/**
 * A playlist row. Podcasts and local files also ride in `items`, so the wrapper type is the filter:
 * only `TrackResponseWrapper` carries a playable Spotify track.
 */
@Serializable
data class SpotifyPathfinderItemV2(
    val __typename: String? = null,
    val data: SpotifyPathfinderTrackDto? = null,
)

/**
 * The track itself. Note this is a **richer** row than the embed's: it carries the album and its cover
 * art ladder, which playlist embeds omit entirely — so tracks imported this way arrive with real
 * per-track artwork instead of waiting on `TrackArtworkEnricher` to borrow one from Deezer.
 */
@Serializable
data class SpotifyPathfinderTrackDto(
    val uri: String? = null,
    val name: String? = null,
    val trackDuration: SpotifyPathfinderDuration? = null,
    val artists: SpotifyPathfinderArtists? = null,
    val albumOfTrack: SpotifyPathfinderAlbum? = null,
)

@Serializable
data class SpotifyPathfinderDuration(val totalMilliseconds: Long? = null)

@Serializable
data class SpotifyPathfinderArtists(val items: List<SpotifyPathfinderArtist> = emptyList())

@Serializable
data class SpotifyPathfinderArtist(val profile: SpotifyPathfinderProfile? = null)

@Serializable
data class SpotifyPathfinderProfile(val name: String? = null)

@Serializable
data class SpotifyPathfinderAlbum(
    val uri: String? = null,
    val name: String? = null,
    val coverArt: SpotifyCoverArtDto? = null,
)
