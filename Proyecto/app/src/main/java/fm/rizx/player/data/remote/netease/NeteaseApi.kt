package fm.rizx.player.data.remote.netease

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * NetEase Cloud Music's public read endpoints (base `https://music.163.com/`). No key, no account — the
 * same endpoints the web player calls. A `Referer` is sent because the host rejects some requests
 * without one.
 *
 * The reason this provider exists: `song/lyric/v1` returns **`yrc`**, a karaoke transcript with a
 * timestamp per word, which LRCLIB has no equivalent of.
 */
interface NeteaseApi {

    /** Full-text song search. `type=1` is songs; durations come back in milliseconds. */
    @Headers("Referer: https://music.163.com")
    @GET("api/search/get")
    suspend fun search(
        @Query("s") query: String,
        @Query("type") type: Int = 1,
        @Query("limit") limit: Int = 10,
    ): NeteaseSearchResponse

    /**
     * Lyrics for a song id. The `yv`/`ytv`/`yrv` flags are what ask for the word-level transcript;
     * `lv` still returns the plain LRC so a song without `yrc` degrades to line timings.
     */
    @Headers("Referer: https://music.163.com")
    @GET("api/song/lyric/v1")
    suspend fun lyric(
        @Query("id") id: Long,
        @Query("cp") cp: Boolean = false,
        @Query("lv") lv: Int = 0,
        @Query("kv") kv: Int = 0,
        @Query("tv") tv: Int = 0,
        @Query("rv") rv: Int = 0,
        @Query("yv") yv: Int = 0,
        @Query("ytv") ytv: Int = 0,
        @Query("yrv") yrv: Int = 0,
    ): NeteaseLyricResponse
}

@Serializable
data class NeteaseSearchResponse(val result: NeteaseSearchResult = NeteaseSearchResult())

@Serializable
data class NeteaseSearchResult(val songs: List<NeteaseSongDto> = emptyList())

@Serializable
data class NeteaseSongDto(
    val id: Long? = null,
    val name: String? = null,
    /** Milliseconds. */
    val duration: Long? = null,
    val artists: List<NeteaseArtistDto> = emptyList(),
    val album: NeteaseAlbumDto? = null,
)

@Serializable
data class NeteaseArtistDto(val name: String? = null)

@Serializable
data class NeteaseAlbumDto(val name: String? = null)

@Serializable
data class NeteaseLyricResponse(
    /** Word-by-word transcript when the song has one. */
    val yrc: NeteaseLyricBody? = null,
    /** Classic line-timed LRC. */
    val lrc: NeteaseLyricBody? = null,
    @SerialName("tlyric") val translated: NeteaseLyricBody? = null,
)

@Serializable
data class NeteaseLyricBody(val lyric: String? = null)
