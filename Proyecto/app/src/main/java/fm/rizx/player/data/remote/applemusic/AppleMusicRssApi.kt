package fm.rizx.player.data.remote.applemusic

import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Apple's public marketing-tools RSS: per-country charts, keyless. Base URL is
 * `https://rss.marketingtools.apple.com/` (see `core/network/NetworkModule`).
 *
 * Storefront-scoped rather than global — the dashboard provider sends the user's own country only
 * with the regional-recommendations consent, and a fixed default otherwise.
 */
interface AppleMusicRssApi {

    /** The country's most-played songs ([country] = 2-letter storefront code, e.g. "mx"). */
    @GET("api/v2/{country}/music/most-played/{limit}/songs.json")
    suspend fun mostPlayedSongs(
        @Path("country") country: String,
        @Path("limit") limit: Int = 25,
    ): AppleRssResponse

    /**
     * The country's top **editorial playlists** — "Today's Hits", "Rap Life", the Essentials. Each row
     * carries a name, a cover and a public `music.apple.com` URL, which is what makes them openable.
     */
    @GET("api/v2/{country}/music/most-played/{limit}/playlists.json")
    suspend fun topPlaylists(
        @Path("country") country: String,
        @Path("limit") limit: Int = 25,
    ): AppleRssResponse

    /** The country's top albums, for the feed's albums row. */
    @GET("api/v2/{country}/music/most-played/{limit}/albums.json")
    suspend fun topAlbums(
        @Path("country") country: String,
        @Path("limit") limit: Int = 25,
    ): AppleRssResponse
}
