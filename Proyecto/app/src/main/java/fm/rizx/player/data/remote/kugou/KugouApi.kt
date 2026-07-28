package fm.rizx.player.data.remote.kugou

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * KuGou's public lyrics service (base `https://lyrics.kugou.com/`). Keyless: search by free text plus
 * duration, then download the chosen candidate.
 *
 * Worth having alongside NetEase because it serves **`krc`** — the other widely-available karaoke format
 * with per-word timings — and the two catalogues miss different songs.
 */
interface KugouApi {

    /**
     * Lyric candidates for a query. [durationMs] is what makes the match trustworthy: the same title
     * exists as single, album cut, live and remix, and only the length tells them apart.
     */
    @GET("search")
    suspend fun search(
        @Query("keyword") keyword: String,
        @Query("duration") durationMs: Long? = null,
        @Query("ver") ver: Int = 1,
        @Query("man") man: String = "yes",
        @Query("client") client: String = "pc",
    ): KugouSearchResponse

    /** The chosen candidate's lyrics. `fmt=krc` is the word-timed format; the body is base64. */
    @GET("download")
    suspend fun download(
        @Query("id") id: String,
        @Query("accesskey") accessKey: String,
        @Query("fmt") fmt: String = "krc",
        @Query("charset") charset: String = "utf8",
        @Query("ver") ver: Int = 1,
        @Query("client") client: String = "pc",
    ): KugouDownloadResponse
}

@Serializable
data class KugouSearchResponse(val candidates: List<KugouCandidateDto> = emptyList())

@Serializable
data class KugouCandidateDto(
    val id: String? = null,
    val accesskey: String? = null,
    val song: String? = null,
    val singer: String? = null,
    /** Milliseconds. */
    val duration: Long? = null,
)

@Serializable
data class KugouDownloadResponse(
    /** base64 of the krc payload (see `KrcParser.decode`). */
    val content: String? = null,
)
