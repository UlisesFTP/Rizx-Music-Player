package fm.rizx.player.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The `(entityType, entityId)` of a pending operation — what a remote change is checked against. */
data class SyncKey(val entityType: String, val entityId: String)

@Dao
interface SyncDao {
    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE entityType != :excludedType")
    suspend fun pendingCountExcluding(excludedType: String): Int

    @Query("SELECT * FROM sync_outbox ORDER BY createdAtIso ASC LIMIT :limit")
    suspend fun pending(limit: Int = 100): List<SyncOutboxEntity>

    /** The batch with one entity type held back — how data saver keeps playlists moving while taste waits. */
    @Query("SELECT * FROM sync_outbox WHERE entityType != :excludedType ORDER BY createdAtIso ASC LIMIT :limit")
    suspend fun pendingExcluding(excludedType: String, limit: Int = 100): List<SyncOutboxEntity>

    @Query("SELECT entityType, entityId FROM sync_outbox")
    suspend fun pendingKeys(): List<SyncKey>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(operation: SyncOutboxEntity)

    @Query("DELETE FROM sync_outbox WHERE operationId IN (:operationIds)")
    suspend fun acknowledge(operationIds: List<String>)

    @Query("UPDATE sync_outbox SET attemptCount = attemptCount + 1 WHERE operationId IN (:operationIds)")
    suspend fun markAttempted(operationIds: List<String>)

    /**
     * Drops operations the server has refused [maxAttempts] times. Without this one rejected row —
     * an over-long id, an oversized document — sat at the head of the queue and blocked it for good.
     */
    @Query("DELETE FROM sync_outbox WHERE attemptCount >= :maxAttempts")
    suspend fun evictExhausted(maxAttempts: Int): Int

    @Query("DELETE FROM sync_outbox")
    suspend fun clearOutbox()

    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE entityType = :entityType AND entityId = :entityId)")
    suspend fun hasPending(entityType: String, entityId: String): Boolean

    @Query("SELECT * FROM sync_state WHERE accountId = :accountId")
    suspend fun state(accountId: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE accountId = :accountId")
    fun observeState(accountId: String): Flow<SyncStateEntity?>

    /** The install's device id, whichever account minted it: one install is one device to the cloud. */
    @Query("SELECT deviceId FROM sync_state LIMIT 1")
    suspend fun anyDeviceId(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveState(state: SyncStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRecovery(snapshot: SyncRecoveryEntity)

    @Query("DELETE FROM sync_recovery WHERE expiresAtIso < :nowIso")
    suspend fun pruneRecovery(nowIso: String)
}
