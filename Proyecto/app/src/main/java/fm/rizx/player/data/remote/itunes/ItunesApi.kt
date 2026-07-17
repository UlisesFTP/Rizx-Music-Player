package fm.rizx.player.data.remote.itunes

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit binding for the two iTunes Search endpoints the app uses. Base URL is
 * `https://itunes.apple.com/` (see `core/network/NetworkModule`). No API key — the service is public;
 * a descriptive `User-Agent` is attached by an OkHttp interceptor. `entity=song` / `media=music`
 * keep results to playable tracks.
 */
interface ItunesApi {

    /** Full-text search. Returns songs matching [term] (imperfect, ranked by iTunes). */
    @GET("search")
    suspend fun search(
        @Query("term") term: String,
        @Query("media") media: String = "music",
        @Query("entity") entity: String = "song",
        @Query("limit") limit: Int = 25,
    ): ItunesSearchResponse

    /** Exact lookup by track id — used for just-in-time stream (preview URL) resolution. */
    @GET("lookup")
    suspend fun lookup(
        @Query("id") id: String,
        @Query("entity") entity: String = "song",
    ): ItunesSearchResponse
}
