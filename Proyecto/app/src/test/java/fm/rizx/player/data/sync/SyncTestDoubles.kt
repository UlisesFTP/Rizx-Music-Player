package fm.rizx.player.data.sync

import fm.rizx.player.data.remote.supabase.SupabaseSyncApi
import fm.rizx.player.data.remote.supabase.SyncChange
import fm.rizx.player.data.remote.supabase.SyncOperationRequest
import fm.rizx.player.data.remote.supabase.SyncRequest
import fm.rizx.player.data.remote.supabase.SyncResponse
import fm.rizx.player.domain.account.AccountProfile
import fm.rizx.player.domain.account.AccountRepository
import fm.rizx.player.domain.account.AccountState
import fm.rizx.player.domain.sync.SyncApplied
import fm.rizx.player.domain.sync.SyncCoordinator
import fm.rizx.player.domain.sync.SyncReason
import fm.rizx.player.domain.sync.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * An in-memory replica of the server's `rizx_sync` RPC: idempotent by operation id, records keyed by
 * `(entity_type, entity_id)` with one monotonic revision, ≤100 operations taken per request, ≤500
 * changes returned per pull — and, like the real thing, blind to who wrote a record, so every push
 * comes straight back in the same response.
 */
class FakeSyncApi : SupabaseSyncApi {
    data class Record(val entityType: String, val entityId: String, val document: JsonElement?, val deleted: Boolean, val revision: Long)

    val records = LinkedHashMap<Pair<String, String>, Record>()
    val requests = mutableListOf<SyncRequest>()
    private val seen = HashSet<String>()
    private var revision = 0L

    /** How many requests still answer HTTP 500 before the server recovers. */
    var failuresLeft = 0

    /** Answer every request with this status — `403` is what a non-permanent session gets. */
    var refuseWith: Int? = null

    /** Extra server-side rejection: an operation this returns false for is skipped and never acknowledged. */
    var accepts: (SyncOperationRequest) -> Boolean = { true }

    fun seed(entityType: String, entityId: String, document: JsonElement?, deleted: Boolean = false): Record {
        val record = Record(entityType, entityId, document, deleted, ++revision)
        records[entityType to entityId] = record
        return record
    }

    override suspend fun sync(authorization: String, request: SyncRequest): Response<SyncResponse> {
        requests += request
        refuseWith?.let { return Response.error(it, "{}".toResponseBody("application/json".toMediaType())) }
        if (failuresLeft > 0) {
            failuresLeft--
            return Response.error(500, "{}".toResponseBody("application/json".toMediaType()))
        }
        val acknowledged = mutableListOf<String>()
        for (op in request.operations.take(100)) {
            if (op.operationId in seen) { acknowledged += op.operationId; continue }
            val valid = op.entityType in SyncDocuments.TYPES && op.entityId.length <= 512 &&
                (op.operation == "DELETE" || op.document != null) && accepts(op)
            if (!valid) continue
            seen += op.operationId
            val deleted = op.operation == "DELETE"
            records[op.entityType to op.entityId] = Record(op.entityType, op.entityId, if (deleted) null else op.document, deleted, ++revision)
            acknowledged += op.operationId
        }
        val changes = records.values.filter { it.revision > request.cursor }.sortedBy { it.revision }.take(500)
            .map { SyncChange(it.entityType, it.entityId, it.document, it.deleted, it.revision) }
        val cursor = maxOf(request.cursor, changes.lastOrNull()?.revision ?: request.cursor)
        return Response.success(SyncResponse(acknowledged, cursor, changes))
    }
}

class FakeAccount(
    initial: AccountState = AccountState.SignedIn(AccountProfile("acct-1", "a@b.c", isAnonymous = false)),
    private val token: String? = "tok",
) : AccountRepository {
    override val state = MutableStateFlow(initial)
    override val configured = true
    var signOuts = 0
    override suspend fun requestEmailOtp(email: String) = Unit
    override suspend fun verifyEmailOtp(email: String, code: String) = Unit
    override suspend fun signInWithGoogle(idToken: String, nonce: String) = Unit
    override suspend fun ensureGuestSession(captchaToken: String?) = AccountProfile("g", null, true)
    override suspend fun accessToken(): String? = token
    override suspend fun signOut() { signOuts++; state.value = AccountState.LocalOnly }
    override suspend fun deleteCloudAccount() = Unit
}

fun signedIn(id: String) = AccountState.SignedIn(AccountProfile(id, "$id@x.y", isAnonymous = false))

class FakeApplier : SyncApplier {
    val applied = mutableListOf<SyncChange>()
    val snapshots = mutableListOf<Pair<String, JsonElement>>()
    override suspend fun apply(change: SyncChange): SyncAppliedKind? {
        applied += change
        return when (change.entityType) {
            SyncDocuments.PLAYLIST -> SyncAppliedKind.PLAYLIST
            SyncDocuments.FAVORITE -> SyncAppliedKind.FAVORITE
            SyncDocuments.TASTE -> SyncAppliedKind.TASTE
            else -> null
        }
    }
    override suspend fun keepRemoteSnapshot(playlistId: String, document: JsonElement) { snapshots += playlistId to document }
}

class FakeJournal(var hasLibrary: Boolean = false, private val queues: suspend () -> Int = { 0 }) : LibraryJournal {
    var journaled = 0
    var wiped = 0
    override suspend fun journalEverything(): Int { journaled++; return queues() }
    override suspend fun hasLocalLibrary(): Boolean = hasLibrary
    override suspend fun wipeLocalLibrary() { wiped++ }
}

class FakeSyncCoordinator(override val pendingCount: Flow<Int> = flowOf(0)) : SyncCoordinator {
    var syncNowCalls = 0
    var inlineCalls = 0
    var periodicCalls = 0
    var stopCalls = 0
    val reasons = mutableListOf<SyncReason>()
    val appliedEvents = MutableSharedFlow<SyncApplied>(extraBufferCapacity = 8)
    override val applied: Flow<SyncApplied> get() = appliedEvents
    override val status: Flow<SyncStatus> = flowOf(SyncStatus())
    override fun syncNow(reason: SyncReason) { syncNowCalls++; reasons += reason }
    override suspend fun syncInline(reason: SyncReason) { inlineCalls++; reasons += reason }
    override fun schedulePeriodic() { periodicCalls++ }
    override fun stop() { stopCalls++ }
}

/** The invalidation channel as a switch the test flips and a flow it feeds. */
class FakeInvalidations : SyncInvalidations {
    /** The user currently listened for, null when the channel is closed. */
    var current: String? = null
        private set
    val connects = mutableListOf<String>()
    private val flow = MutableSharedFlow<SyncInvalidation>(extraBufferCapacity = 8)
    override val events: Flow<SyncInvalidation> = flow
    override fun connect(userId: String) {
        if (current == userId) return
        current = userId
        connects += userId
    }
    override fun disconnect() { current = null }
    fun nudge(revision: Long, deviceId: String?) = flow.tryEmit(SyncInvalidation(revision, deviceId))
}
