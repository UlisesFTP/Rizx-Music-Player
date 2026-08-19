package fm.rizx.player.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM sync_outbox ORDER BY createdAtIso ASC LIMIT :limit")
    suspend fun pending(limit: Int = 100): List<SyncOutboxEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(operation: SyncOutboxEntity)

    @Query("DELETE FROM sync_outbox WHERE operationId IN (:operationIds)")
    suspend fun acknowledge(operationIds: List<String>)

    @Query("UPDATE sync_outbox SET attemptCount = attemptCount + 1 WHERE operationId IN (:operationIds)")
    suspend fun markAttempted(operationIds: List<String>)

    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE entityType = :entityType AND entityId = :entityId)")
    suspend fun hasPending(entityType: String, entityId: String): Boolean

    @Query("SELECT * FROM sync_state WHERE accountId = :accountId")
    suspend fun state(accountId: String): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveState(state: SyncStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRecovery(snapshot: SyncRecoveryEntity)

    @Query("DELETE FROM sync_recovery WHERE expiresAtIso < :nowIso")
    suspend fun pruneRecovery(nowIso: String)
}
