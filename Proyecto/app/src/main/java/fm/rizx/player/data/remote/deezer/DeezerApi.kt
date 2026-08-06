package fm.rizx.player.data.remote.deezer

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit binding for the keyless Deezer public API (base `https://api.deezer.com/`). No API key —
 * public read endpoints. `/chart` + `/editorial` (dashboard) are added in Phase 19.
 */
interface DeezerApi {

    @GET("search")
    suspend fun searchTracks(@Query("q") query: String, @Query("limit") limit: Int): DeezerSearchResponse

    /** Dedicated search indexes (keyless) — richer, ranked results for the Artists/Albums/Playlists tabs. */
    @GET("search/artist")
    suspend fun searchArtists(@Query("q") query: String, @Query("limit") limit: Int): DeezerArtistsWrapper

    @GET("search/album")
    suspend fun searchAlbums(@Query("q") query: String, @Query("limit") limit: Int): DeezerAlbumsResponse

    @GET("search/playlist")
    suspend fun searchPlaylists(@Query("q") query: String, @Query("limit") limit: Int): DeezerPlaylistsWrapper

    /** One track by its Deezer id — the "owner-first" lookup, exact and unranked. */
    @GET("track/{id}")
    suspend fun track(@Path("id") id: String): DeezerTrackDto

    /**
     * One track by its **ISRC** — the recording's own identifier, so this is an identity lookup rather
     * than a search: no ranking, no near-misses, no same-titled song by somebody else.
     *
     * Deezer answers an unknown ISRC with a `200` carrying an `error` object, which the lenient parser
     * turns into an all-null DTO and the mapper into `null`. That is the intended path, not a bug.
     */
    @GET("track/isrc:{isrc}")
    suspend fun trackByIsrc(@Path("isrc") isrc: String): DeezerTrackDto

    @GET("album/{id}")
    suspend fun album(@Path("id") id: String): DeezerAlbumDto

    @GET("artist/{id}")
    suspend fun artist(@Path("id") id: String): DeezerArtistDto

    @GET("artist/{id}/top")
    suspend fun artistTop(@Path("id") id: String, @Query("limit") limit: Int): DeezerSearchResponse

    /** ~25 tracks "in the style of" the artist — the seed for the feed/search radio (contextual next/prev). */
    @GET("artist/{id}/radio")
    suspend fun artistRadio(@Path("id") id: String, @Query("limit") limit: Int): DeezerSearchResponse

    /**
     * One page of an artist's discography. [index] is the offset: a prolific artist has far more
     * releases than one page holds, and the artist screen shows the whole discography.
     */
    @GET("artist/{id}/albums")
    suspend fun artistAlbums(
        @Path("id") id: String,
        @Query("limit") limit: Int,
        @Query("index") index: Int = 0,
    ): DeezerAlbumsResponse

    /** Artists similar to {id} — feeds the personalized "Artists for you" Home row. */
    @GET("artist/{id}/related")
    suspend fun artistRelated(@Path("id") id: String, @Query("limit") limit: Int): DeezerArtistsWrapper

    /** Top tracks/albums/artists/playlists in one call (dashboard, Phase 19). */
    @GET("chart")
    suspend fun chart(): DeezerChartDto

    /** Full playlist with its tracks (URL import, Phase 22). Embeds only the first 400 — see [playlistTracks]. */
    @GET("playlist/{id}")
    suspend fun playlist(@Path("id") id: String): DeezerPlaylistFullDto

    /**
     * One page of a playlist's tracks.
     *
     * Needed because `/playlist/{id}` caps its embedded `tracks.data` at **400** *and returns no `next`
     * link*, so a longer playlist looks complete when it isn't (verified: a 1640-track playlist reports
     * `nb_tracks: 1640` and hands back 400). This endpoint reports the true `total` and pages properly.
     */
    @GET("playlist/{id}/tracks")
    suspend fun playlistTracks(
        @Path("id") id: String,
        @Query("index") index: Int,
        @Query("limit") limit: Int,
    ): DeezerPagedTracksDto

    /**
     * Deezer's curated "Top radios" — its mood & genre stations ("Chill Out", "¡Fiesta!", Pop, 80's…),
     * titles localized per detected region by Deezer itself. Preferred over the raw `/radio` list,
     * which mixes in third-party branded stations; this one is the set Deezer's own apps feature.
     * Titles repeat across regions ("Hits" twice, verified live) — callers dedupe by title.
     */
    @GET("radio/lists")
    suspend fun radioLists(@Query("limit") limit: Int): DeezerRadiosWrapper

    /** What a station is serving right now — plain track rows, same shape as search results. */
    @GET("radio/{id}/tracks")
    suspend fun radioTracks(@Path("id") id: String, @Query("limit") limit: Int): DeezerSearchResponse
}
