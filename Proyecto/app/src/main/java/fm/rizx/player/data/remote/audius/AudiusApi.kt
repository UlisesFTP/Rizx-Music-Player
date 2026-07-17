package fm.rizx.player.data.remote.audius

import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Retrofit binding for the keyless Audius API. The base host is *dynamic* (chosen by
 * [AudiusHostProvider] from discovery), so every call takes a full [Url]; the Retrofit base URL is only a
 * placeholder. `app_name` is required by Audius on every request; no API key is used.
 */
interface AudiusApi {

    /** Discovery: pass the full `https://api.audius.co` URL; returns the gateway host list. */
    @GET
    suspend fun hosts(@Url url: String): AudiusHostsResponse

    /** Track text search: [url] = `"{host}/v1/tracks/search"`. */
    @GET
    suspend fun searchTracks(
        @Url url: String,
        @Query("query") query: String,
        @Query("app_name") appName: String,
        @Query("limit") limit: Int,
    ): AudiusTracksResponse

    /** Trending tracks (used by the dashboard provider later): [url] = `"{host}/v1/tracks/trending"`. */
    @GET
    suspend fun trending(
        @Url url: String,
        @Query("app_name") appName: String,
        @Query("limit") limit: Int,
    ): AudiusTracksResponse
}
