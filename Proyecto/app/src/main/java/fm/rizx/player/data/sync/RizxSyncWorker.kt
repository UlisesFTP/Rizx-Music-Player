package fm.rizx.player.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.data.local.db.SyncDao
import fm.rizx.player.data.local.db.SyncStateEntity
import fm.rizx.player.data.remote.supabase.SupabaseSyncApi
import fm.rizx.player.data.remote.supabase.SyncOperationRequest
import fm.rizx.player.data.remote.supabase.SyncRequest
import fm.rizx.player.domain.account.AccountRepository
import fm.rizx.player.domain.account.AccountState
import fm.rizx.player.domain.repository.PlaylistExportFormat
import fm.rizx.player.domain.repository.PlaylistExportRepository
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

class RizxSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun accountRepository(): AccountRepository
        fun syncDao(): SyncDao
        fun syncApi(): SupabaseSyncApi
        fun playlistExportRepository(): PlaylistExportRepository
        fun playlistSyncEngine(): PlaylistSyncEngine
        fun json(): Json
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        val account = deps.accountRepository()
        val signedIn = account.state.value as? AccountState.SignedIn ?: return Result.success()
        if (signedIn.profile.isAnonymous) return Result.success()
        val token = account.accessToken() ?: return Result.retry()
        val syncDao = deps.syncDao()
        val current = syncDao.state(signedIn.profile.id)
        val state = current ?: SyncStateEntity(signedIn.profile.id, UUID.randomUUID().toString())
        val pending = syncDao.pending()
        val operations = pending.mapNotNull { item ->
            val document = when {
                item.operation == "DELETE" -> null
                item.entityType == "PLAYLIST" -> deps.playlistExportRepository()
                    .export(item.entityId, PlaylistExportFormat.RIZX_JSON)?.content
                    ?.let(deps.json()::parseToJsonElement)
                item.payloadJson != null -> deps.json().parseToJsonElement(item.payloadJson)
                else -> return@mapNotNull null
            }
            SyncOperationRequest(item.operationId, item.entityType, item.entityId, item.operation, document)
        }
        if (pending.isNotEmpty()) syncDao.markAttempted(pending.map { it.operationId })
        return runCatching {
            val response = deps.syncApi().sync("Bearer $token", SyncRequest(state.deviceId, state.cursor, operations))
            if (!response.isSuccessful) error("Sync failed (${response.code()})")
            val body = response.body() ?: error("Sync returned an empty response")
            body.changes.sortedBy { it.revision }.forEach { deps.playlistSyncEngine().apply(it) }
            if (body.acknowledged.isNotEmpty()) syncDao.acknowledge(body.acknowledged)
            syncDao.saveState(state.copy(cursor = body.cursor, lastSyncedAtIso = Instant.now().toString()))
            syncDao.pruneRecovery(Instant.now().toString())
            Result.success()
        }.getOrElse { if (runAttemptCount < 5) Result.retry() else Result.failure() }
    }
}
