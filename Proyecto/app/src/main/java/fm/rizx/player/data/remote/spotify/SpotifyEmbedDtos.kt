package fm.rizx.player.data.remote.spotify

import kotlinx.serialization.Serializable

/**
 * DTOs for a Spotify playlist's **public embed page** (`open.spotify.com/embed/playlist/<id>`), whose
 * `__NEXT_DATA__` script carries the tracklist as JSON. This is the **keyless** path — no API key, no
 * token, no login (verified against the live page), unlike upstream Nuclear which embeds a rotating TOTP
 * secret to mint an anonymous bearer.
 *
 * Shape: `props.pageProps.state.data.entity` → `{ name, type:"playlist", subtitle, trackList[] }`.
 * Only the fields we map are declared; the blob carries many more and the shared `Json` ignores unknown keys.
 */
@Serializable
data class SpotifyEmbedNextData(val props: SpotifyEmbedProps? = null)

@Serializable
data class SpotifyEmbedProps(val pageProps: SpotifyEmbedPageProps? = null)

@Serializable
data class SpotifyEmbedPageProps(val state: SpotifyEmbedState? = null)

@Serializable
data class SpotifyEmbedState(val data: SpotifyEmbedData? = null)

@Serializable
data class SpotifyEmbedData(val entity: SpotifyEmbedEntity? = null)

@Serializable
data class SpotifyEmbedEntity(
    val name: String? = null,
    val type: String? = null,
    val uri: String? = null,
    val subtitle: String? = null,
    val trackList: List<SpotifyEmbedTrackDto> = emptyList(),
)

/** One tracklist row. [duration] is **milliseconds**; [uri] is `spotify:track:<id>`; [subtitle] the artists. */
@Serializable
data class SpotifyEmbedTrackDto(
    val uri: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val duration: Long? = null,
    val isPlayable: Boolean? = null,
)
