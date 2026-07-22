package fm.rizx.player.data.remote.lrclib

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Binding for the public, keyless LRCLIB API (base `https://lrclib.net/`) — the one lyrics source that
 * serves **timed** lyrics without credentials. (Deezer's public API exposes no lyrics at all, only
 * explicit-content flags; Musixmatch requires a key. See the plan's findings.)
 *
 * LRCLIB asks callers to identify themselves; the shared client's `User-Agent` interceptor already does
 * (`NetworkModule`), so no per-call header is needed. DTOs stay in this layer (ADR 0006).
 */
interface LrcLibApi {

    /**
     * Exact lookup. **[durationSeconds] is matched within ±2 s** — passing a duration that disagrees
     * with LRCLIB's copy returns 404 rather than a loose match, so the caller must be ready to retry
     * without it.
     */
    @GET("api/get")
    suspend fun get(
        @Query("artist_name") artist: String,
        @Query("track_name") title: String,
        @Query("album_name") album: String? = null,
        @Query("duration") durationSeconds: Long? = null,
    ): LrcLibTrackDto

    /** Free-text search. Every row already embeds its full lyrics, so a pick needs no follow-up call. */
    @GET("api/search")
    suspend fun search(@Query("q") query: String): List<LrcLibTrackDto>
}

@Serializable
data class LrcLibTrackDto(
    val id: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    /** Seconds, fractional (e.g. `367.0`). */
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)
