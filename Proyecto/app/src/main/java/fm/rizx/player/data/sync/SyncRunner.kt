package fm.rizx.player.data.sync

import fm.rizx.player.data.local.db.SyncDao
import fm.rizx.player.data.local.db.SyncKey
import fm.rizx.player.data.local.db.SyncOutboxEntity
import fm.rizx.player.data.local.db.SyncStateEntity
import fm.rizx.player.data.local.store.SyncPrefsStore
import fm.rizx.player.data.remote.supabase.SupabaseSyncApi
import fm.rizx.player.data.remote.supabase.SyncChange
import fm.rizx.player.data.remote.supabase.SyncOperationRequest
import fm.rizx.player.data.remote.supabase.SyncRequest
import fm.rizx.player.domain.account.AccountRepository
import fm.rizx.player.domain.account.AccountState
import fm.rizx.player.domain.repository.PlaylistExportFormat
import fm.rizx.player.domain.repository.PlaylistExportRepository
import fm.rizx.player.domain.sync.SyncApplied
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.util.UUID

/**
 * One sync run, start to finish: backfill if the account has never seen this library, drain the outbox
 * in batches, apply the server's pages of changes, and say what moved.
 *
 * Plain class, no Android in it: the WorkManager worker is a shell around [run], and the tests drive
 * this directly against an in-memory replica of the edge function.
 *
 * What it guards against, each of which was a real defect of the first cut:
 * - **Echo.** The server does not know which device wrote a record, so every push comes straight back
 *   in the same response's `changes`. Own taste rows are recognised by their device prefix; anything
 *   else is compared with what was just sent and skipped when equal.
 * - **A dirty local entity.** A remote playlist or favorite whose key still has a pending local edit is
 *   not applied — the local edit is about to overwrite it anyway. The remote playlist is kept as a
 *   30-day snapshot so the loser is recoverable, as spec 021 requires.
 * - **A stuck queue.** Operations the server keeps refusing are dropped after [MAX_ATTEMPTS]; ones this
 *   client can see are invalid never leave. A round that sends and gets nothing acknowledged ends the
 *   run instead of spinning.
 * - **Partial runs.** Batches of [BATCH] and pages of [PAGE] are looped until both sides are empty.
 */
class SyncRunner(
    private val account: AccountRepository,
    private val syncDao: SyncDao,
    private val api: SupabaseSyncApi,
    private val exports: PlaylistExportRepository,
    private val applier: SyncApplier,
    private val journal: LibraryJournal,
    private val prefs: SyncPrefsStore,
    private val events: SyncEvents,
    private val json: Json,
    /** Data saver on a metered link: taste rows wait, playlists and favorites still go. */
    private val tasteUploadsPaused: () -> Boolean = { false },
    private val now: () -> Instant = { Instant.now() },
    private val newDeviceId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String) -> Unit = {},
) {
    enum class Outcome { SUCCESS, RETRY }

    /**
     * One run at a time. The one-shot and the periodic requests are different WorkManager work, so
     * they can start together — and did, pushing the same batch twice in parallel until both timed
     * out. The second run waits, finds the outbox drained, and only pulls.
     */
    private val gate = Mutex()

    suspend fun run(): Outcome = gate.withLock { runLocked() }

    private suspend fun runLocked(): Outcome {
        val signedIn = account.state.value as? AccountState.SignedIn ?: return Outcome.SUCCESS
        if (signedIn.profile.isAnonymous) return Outcome.SUCCESS
        val accountId = signedIn.profile.id
        val token = account.accessToken() ?: return Outcome.RETRY
        val state = syncDao.state(accountId)
            ?: SyncStateEntity(accountId, syncDao.anyDeviceId() ?: newDeviceId()).also { syncDao.saveState(it) }
        events.started()
        return try {
            val applied = drain(token, state)
            syncDao.pruneRecovery(now().toString())
            prefs.setLastAccountId(accountId)
            events.finished(failed = false)
            events.applied(applied)
            Outcome.SUCCESS
        } catch (e: CancellationException) {
            events.finished(failed = false)
            throw e
        } catch (e: Exception) {
            log("sync run failed: ${e.message}")
            events.finished(failed = true)
            Outcome.RETRY
        }
    }

    private suspend fun drain(token: String, initial: SyncStateEntity): SyncApplied {
        var state = initial
        if (state.backfilledAtIso == null) {
            val queued = journal.journalEverything()
            state = state.copy(backfilledAtIso = now().toString())
            syncDao.saveState(state)
            log("backfill queued $queued operations")
        }
        syncDao.evictExhausted(MAX_ATTEMPTS).takeIf { it > 0 }?.let { log("dropped $it operations after $MAX_ATTEMPTS refusals") }

        var playlists = 0
        var favorites = 0
        var taste = 0
        for (round in 0 until MAX_ROUNDS) {
            val pauseTaste = tasteUploadsPaused()
            val pending = if (pauseTaste) syncDao.pendingExcluding(SyncDocuments.TASTE, BATCH) else syncDao.pending(BATCH)
            val invalid = mutableListOf<String>()
            val sent = HashMap<SyncKey, JsonElement?>()
            val operations = pending.mapNotNull { item ->
                val request = toRequest(item, state.deviceId)
                if (request == null) invalid += item.operationId
                else sent[SyncKey(request.entityType, request.entityId)] = request.document
                request
            }
            if (invalid.isNotEmpty()) {
                syncDao.acknowledge(invalid)
                log("dropped ${invalid.size} operations this client cannot send")
            }
            if (operations.isNotEmpty()) syncDao.markAttempted(operations.map { it.operationId })

            val response = api.sync("Bearer $token", SyncRequest(state.deviceId, state.cursor, operations))
            if (!response.isSuccessful) error("Sync failed (${response.code()})")
            val body = response.body() ?: error("Sync returned an empty response")
            if (body.acknowledged.isNotEmpty()) syncDao.acknowledge(body.acknowledged)

            val counts = applyChanges(body.changes, sent, state.deviceId)
            playlists += counts[0]; favorites += counts[1]; taste += counts[2]

            state = state.copy(cursor = maxOf(state.cursor, body.cursor), lastSyncedAtIso = now().toString())
            syncDao.saveState(state)

            val remaining = if (pauseTaste) syncDao.pendingCountExcluding(SyncDocuments.TASTE) else syncDao.pendingCount()
            if (remaining == 0 && body.changes.size < PAGE) break
            // Sent a batch and the server took none of it: retrying now would only repeat the refusal.
            if (operations.isNotEmpty() && body.acknowledged.isEmpty() && body.changes.size < PAGE) break
        }
        return SyncApplied(playlists, favorites, taste)
    }

    /** The wire form of an outbox row, or null when it cannot be sent and must be dropped. */
    private suspend fun toRequest(item: SyncOutboxEntity, deviceId: String): SyncOperationRequest? {
        if (!UUID_ID.matches(item.operationId) || item.entityType !in SyncDocuments.TYPES || item.operation !in OPERATIONS) return null
        val entityId = if (item.entityType == SyncDocuments.TASTE) TasteKeys.wireId(deviceId, item.entityId) else item.entityId
        if (entityId.isEmpty() || entityId.length > MAX_ID_LENGTH) return null
        val document: JsonElement? = when {
            item.operation == DELETE -> null
            // Playlists are exported at send time so the document is the list as it is now, not as it
            // was when the edit was journaled. Gone since then means there is nothing to say.
            item.entityType == SyncDocuments.PLAYLIST -> exports.export(item.entityId, PlaylistExportFormat.RIZX_JSON)?.content
                ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: return null
            item.payloadJson != null -> runCatching { json.parseToJsonElement(item.payloadJson) }.getOrNull() ?: return null
            else -> return null
        }
        if (document != null && document.toString().length > MAX_DOCUMENT_CHARS) return null
        return SyncOperationRequest(item.operationId, item.entityType, entityId, item.operation, document)
    }

    /** Returns applied counts as `[playlists, favorites, taste]`. */
    private suspend fun applyChanges(changes: List<SyncChange>, sent: Map<SyncKey, JsonElement?>, deviceId: String): IntArray {
        val counts = IntArray(3)
        if (changes.isEmpty()) return counts
        val dirty = syncDao.pendingKeys().toHashSet()
        for (change in changes.sortedBy { it.revision }) {
            val key = SyncKey(change.entityType, change.entityId)
            if (change.entityType == SyncDocuments.TASTE && TasteKeys.isOwn(change.entityId, deviceId)) continue
            if (change.entityType != SyncDocuments.TASTE && key in dirty) {
                if (change.entityType == SyncDocuments.PLAYLIST && !change.deleted && change.document != null) {
                    applier.keepRemoteSnapshot(change.entityId, change.document)
                }
                continue
            }
            if (key in sent) {
                val ours = sent[key]
                val echo = change.deleted == (ours == null) &&
                    (change.deleted || SyncDocuments.same(change.entityType, ours, change.document))
                if (echo) continue
            }
            when (applier.apply(change)) {
                SyncAppliedKind.PLAYLIST -> counts[0]++
                SyncAppliedKind.FAVORITE -> counts[1]++
                SyncAppliedKind.TASTE -> counts[2]++
                null -> Unit
            }
        }
        return counts
    }

    companion object {
        /** Under the server's 100: it applies operations one RPC at a time, and a full hundred ran past the read timeout. */
        const val BATCH = 50
        const val PAGE = 500
        const val MAX_ROUNDS = 20
        const val MAX_ATTEMPTS = 8
        const val MAX_ID_LENGTH = 512
        const val MAX_DOCUMENT_CHARS = 5 * 1024 * 1024
        private const val DELETE = "DELETE"
        private val OPERATIONS = setOf("UPSERT", DELETE)
        private val UUID_ID = Regex("^[0-9a-fA-F-]{36}$")
    }
}
