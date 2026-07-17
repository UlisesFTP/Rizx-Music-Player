package fm.rizx.player.data.remote.lyricsovh

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Binding for the public, keyless lyrics.ovh API (base `https://api.lyrics.ovh/`). `GET /v1/{artist}/
 * {title}` returns the lyrics or a 404 with an `error` message when none are found. DTOs stay in this
 * layer (ADR 0006).
 */
interface LyricsOvhApi {
    @GET("v1/{artist}/{title}")
    suspend fun getLyrics(@Path("artist") artist: String, @Path("title") title: String): LyricsOvhResponse
}

@Serializable
data class LyricsOvhResponse(
    val lyrics: String? = null,
    val error: String? = null,
)
