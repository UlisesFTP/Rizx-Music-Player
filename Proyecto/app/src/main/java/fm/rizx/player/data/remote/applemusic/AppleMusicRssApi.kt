package fm.rizx.player.data.remote.applemusic

import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Apple's public marketing-tools RSS: per-country most-played charts, keyless. Base URL is
 * `https://rss.marketingtools.apple.com/` (see `core/network/NetworkModule`). Regional only —
 * there is no "global" storefront, which is why the dashboard provider hides itself entirely
 * without the regional-recommendations consent.
 */
interface AppleMusicRssApi {

    /** The country's most-played songs ([country] = 2-letter storefront code, e.g. "mx"). */
    @GET("api/v2/{country}/music/most-played/{limit}/songs.json")
    suspend fun mostPlayedSongs(
        @Path("country") country: String,
        @Path("limit") limit: Int = 25,
    ): AppleRssResponse
}
