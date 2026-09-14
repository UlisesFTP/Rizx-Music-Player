package fm.rizx.player.data.remote.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

@Serializable
data class SyncOperationRequest(
    @SerialName("operation_id") val operationId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val operation: String,
    val document: JsonElement? = null,
)

/** The named parameters of `public.rizx_sync`; PostgREST maps the keys onto the function's arguments. */
@Serializable
data class SyncRequest(
    @SerialName("p_device_id") val deviceId: String,
    @SerialName("p_cursor") val cursor: Long,
    @SerialName("p_operations") val operations: List<SyncOperationRequest>,
)

@Serializable
data class SyncChange(
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val document: JsonElement? = null,
    val deleted: Boolean,
    val revision: Long,
)

@Serializable
data class SyncResponse(
    val acknowledged: List<String> = emptyList(),
    val cursor: Long,
    val changes: List<SyncChange> = emptyList(),
)

/**
 * Account sync as one PostgREST RPC: `public.rizx_sync` applies the batch and returns the next page of
 * changes in a single transaction, serialized per account (see the migration for why). It replaced the
 * `functions/v1/sync` Edge Function, which applied each operation with its own round trip and counted
 * against the invocation quota. `403` means the session is not a permanent account.
 */
interface SupabaseSyncApi {
    @POST("rest/v1/rpc/rizx_sync")
    suspend fun sync(
        @Header("Authorization") authorization: String,
        @Body request: SyncRequest,
    ): Response<SyncResponse>
}
