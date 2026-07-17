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

    @GET("album/{id}")
    suspend fun album(@Path("id") id: String): DeezerAlbumDto

    @GET("artist/{id}")
    suspend fun artist(@Path("id") id: String): DeezerArtistDto

    @GET("artist/{id}/top")
    suspend fun artistTop(@Path("id") id: String, @Query("limit") limit: Int): DeezerSearchResponse

    /** ~25 tracks "in the style of" the artist — the seed for the feed/search radio (contextual next/prev). */
    @GET("artist/{id}/radio")
    suspend fun artistRadio(@Path("id") id: String, @Query("limit") limit: Int): DeezerSearchResponse

    @GET("artist/{id}/albums")
    suspend fun artistAlbums(@Path("id") id: String, @Query("limit") limit: Int): DeezerAlbumsResponse

    /** Top tracks/albums/artists/playlists in one call (dashboard, Phase 19). */
    @GET("chart")
    suspend fun chart(): DeezerChartDto

    /** Full playlist with its tracks (URL import, Phase 22). */
    @GET("playlist/{id}")
    suspend fun playlist(@Path("id") id: String): DeezerPlaylistFullDto
}
