package fm.rizx.player.data.remote.deezer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the keyless Deezer public API (`https://api.deezer.com`). Durations are seconds; cover/
 * picture URLs come in fixed sizes. DTOs stay in this layer (ADR 0006); the lenient shared `Json` drops
 * the many fields we don't use.
 */

@Serializable
data class DeezerSearchResponse(val data: List<DeezerTrackDto> = emptyList())

@Serializable
data class DeezerAlbumsResponse(val data: List<DeezerAlbumShortDto> = emptyList())

@Serializable
data class DeezerTrackDto(
    val id: Long? = null,
    val title: String? = null,
    /** Seconds. */
    val duration: Int? = null,
    val artist: DeezerArtistShortDto? = null,
    val album: DeezerAlbumShortDto? = null,
    val preview: String? = null,
    @SerialName("track_position") val trackPosition: Int? = null,
)

@Serializable
data class DeezerArtistShortDto(
    val id: Long? = null,
    val name: String? = null,
    @SerialName("picture_xl") val pictureXl: String? = null,
    @SerialName("picture_big") val pictureBig: String? = null,
    @SerialName("picture_medium") val pictureMedium: String? = null,
    /**
     * Followers. Present on `search/artist` rows and it is the only thing that separates an artist
     * from their **duplicate entries** — Deezer carries a second "The Weeknd" with 27 fans and one
     * album next to the real one with 14.6M, and ranks it *first*. See [DeezerArtistSearch].
     */
    @SerialName("nb_fan") val nbFan: Long? = null,
)

@Serializable
data class DeezerAlbumShortDto(
    val id: Long? = null,
    val title: String? = null,
    @SerialName("cover_xl") val coverXl: String? = null,
    @SerialName("cover_big") val coverBig: String? = null,
    @SerialName("cover_medium") val coverMedium: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    /**
     * "album" | "single" | "ep" | "compilation". Deezer publishes it on `/artist/{id}/albums`, which
     * is what lets the artist page separate a studio album from a two-track single instead of listing
     * forty entries in one row.
     */
    @SerialName("record_type") val recordType: String? = null,
    /** Present on `search/album` rows (the album's main artist); absent on track-nested albums. */
    val artist: DeezerArtistShortDto? = null,
)

/** `/album/{id}` — full album with its track list. */
@Serializable
data class DeezerAlbumDto(
    val id: Long? = null,
    val title: String? = null,
    @SerialName("cover_xl") val coverXl: String? = null,
    @SerialName("cover_big") val coverBig: String? = null,
    @SerialName("cover_medium") val coverMedium: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("nb_tracks") val nbTracks: Int? = null,
    /** Seconds. */
    val duration: Int? = null,
    val artist: DeezerArtistShortDto? = null,
    val tracks: DeezerTracksWrapper? = null,
    /**
     * Deezer's genres, and the **only** place in its API they appear — a track row carries none. This is
     * what lets the automatic equalizer know a song's family without a second catalogue.
     */
    val genres: DeezerGenresWrapper? = null,
)

@Serializable
data class DeezerTracksWrapper(val data: List<DeezerTrackDto> = emptyList())

@Serializable
data class DeezerGenresWrapper(val data: List<DeezerGenreDto> = emptyList())

@Serializable
data class DeezerGenreDto(val id: Long? = null, val name: String? = null)

/** `/artist/{id}` — artist header. */
@Serializable
data class DeezerArtistDto(
    val id: Long? = null,
    val name: String? = null,
    @SerialName("picture_xl") val pictureXl: String? = null,
    @SerialName("picture_big") val pictureBig: String? = null,
    @SerialName("picture_medium") val pictureMedium: String? = null,
    @SerialName("nb_fan") val nbFan: Long? = null,
)

/** `/chart` — top tracks/albums/artists/playlists in one keyless call (Phase 19 dashboard). */
@Serializable
data class DeezerChartDto(
    val tracks: DeezerTracksWrapper = DeezerTracksWrapper(),
    val albums: DeezerAlbumsResponse = DeezerAlbumsResponse(),
    val artists: DeezerArtistsWrapper = DeezerArtistsWrapper(),
    val playlists: DeezerPlaylistsWrapper = DeezerPlaylistsWrapper(),
)

@Serializable
data class DeezerArtistsWrapper(val data: List<DeezerArtistShortDto> = emptyList())

@Serializable
data class DeezerPlaylistsWrapper(val data: List<DeezerPlaylistDto> = emptyList())

@Serializable
data class DeezerPlaylistDto(
    val id: Long? = null,
    val title: String? = null,
    @SerialName("picture_xl") val pictureXl: String? = null,
    @SerialName("picture_big") val pictureBig: String? = null,
    @SerialName("picture_medium") val pictureMedium: String? = null,
    @SerialName("nb_tracks") val nbTracks: Int? = null,
)

/** `/playlist/{id}` — full playlist with its track list (Phase 22 URL import). */
@Serializable
data class DeezerPlaylistFullDto(
    val id: Long? = null,
    val title: String? = null,
    val description: String? = null,
    val tracks: DeezerTracksWrapper? = null,
    /** The playlist's real length. `tracks.data` stops at 400, so this is how truncation is detected. */
    @SerialName("nb_tracks") val nbTracks: Int? = null,
)

/** `/playlist/{id}/tracks` — a page, with the true total so paging knows when to stop. */
@Serializable
data class DeezerPagedTracksDto(
    val data: List<DeezerTrackDto> = emptyList(),
    val total: Int? = null,
)

/** `/radio/lists` — the curated mood & genre stations. */
@Serializable
data class DeezerRadiosWrapper(val data: List<DeezerRadioDto> = emptyList())

@Serializable
data class DeezerRadioDto(
    val id: Long? = null,
    val title: String? = null,
    @SerialName("picture_medium") val pictureMedium: String? = null,
)
