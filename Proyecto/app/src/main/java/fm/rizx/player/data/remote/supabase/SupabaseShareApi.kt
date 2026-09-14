package fm.rizx.player.data.remote.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

@Serializable
data class CreateShareRequest(
    val document: JsonElement,
    @SerialName("expires_in_days") val expiresInDays: Int,
)

@Serializable
data class ShareResponse(
    val id: String,
    val url: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable data class RevokeShareRequest(@SerialName("share_id") val shareId: String)

interface SupabaseShareApi {
    companion object {
        /** The function that owns shares. `GET <READ_PATH>/<token>` returns a shared playlist document. */
        const val READ_PATH = "functions/v1/playlist-shares"
    }

    @POST("$READ_PATH/create")
    suspend fun create(
        @Header("Authorization") authorization: String,
        @Body request: CreateShareRequest,
    ): Response<ShareResponse>

    @GET("$READ_PATH/list")
    suspend fun list(@Header("Authorization") authorization: String): Response<List<ShareResponse>>

    @POST("$READ_PATH/revoke")
    suspend fun revoke(
        @Header("Authorization") authorization: String,
        @Body request: RevokeShareRequest,
    ): Response<Unit>
}

