package fm.rizx.player.data.remote.itunes

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit binding for the iTunes Search endpoints the app uses. Base URL is
 * `https://itunes.apple.com/` (see `core/network/NetworkModule`). No API key — the service is public
 * and documented; a descriptive `User-Agent` is attached by an OkHttp interceptor.
 *
 * The same two endpoints back the whole Apple Music catalogue surface: `search` with `entity` set to
 * `song`/`musicArtist`/`album` covers the three search tabs, and `lookup` with `entity` pivots an id
 * into its children (artist → albums or songs, album → tracks). That is why there is no Apple web-player
 * token anywhere in this app: everything needed is on the public API (ADR 0018).
 */
interface ItunesApi {

    /**
     * Full-text search. [entity] selects what comes back (`song`, `musicArtist`, `album`);
     * [attribute] narrows what [term] is matched against (e.g. `genreTerm` for genre browsing).
     */
    @GET("search")
    suspend fun search(
        @Query("term") term: String,
        @Query("media") media: String = "music",
        @Query("entity") entity: String = "song",
        @Query("limit") limit: Int = 25,
        @Query("attribute") attribute: String? = null,
        @Query("country") country: String? = null,
    ): ItunesSearchResponse

    /**
     * Exact lookup by id — used for just-in-time stream (preview URL) resolution and, with [entity]
     * set, to expand an id into its children. The first row is the looked-up entity itself; the rest
     * are the children.
     */
    @GET("lookup")
    suspend fun lookup(
        @Query("id") id: String,
        @Query("entity") entity: String = "song",
        @Query("limit") limit: Int = 25,
        @Query("country") country: String? = null,
    ): ItunesSearchResponse
}
