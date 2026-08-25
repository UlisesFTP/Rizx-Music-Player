package fm.rizx.player.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentlyPlayedDao {

    /** Insert or bump: replaying a track replaces its row (new [RecentlyPlayedEntity.playedAtIso]). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RecentlyPlayedEntity)

    @Query("DELETE FROM sync_outbox WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun deleteSyncOperationsFor(entityType: String, entityId: String)

    @Insert
    suspend fun insertSyncOperationRow(operation: SyncOutboxEntity)

    /**
     * One pending operation per track. Every play start and every outcome journals a full snapshot of
     * the row, so only the newest matters — a listening session used to outrun the 100-row batch.
     */
    @Transaction
    suspend fun insertSyncOperation(operation: SyncOutboxEntity) {
        deleteSyncOperationsFor(operation.entityType, operation.entityId)
        insertSyncOperationRow(operation)
    }

    @Transaction
    suspend fun upsertWithJournal(entry: RecentlyPlayedEntity, operation: SyncOutboxEntity) {
        upsert(entry)
        insertSyncOperation(operation)
    }

    /** Clearing history tells the cloud too, one delete per row this device owns. */
    @Transaction
    suspend fun clearWithJournal(operations: List<SyncOutboxEntity>) {
        clear()
        operations.forEach { insertSyncOperation(it) }
    }

    /**
     * The row for one track, or null. The repository reads it before writing so the v4 counters are
     * *summed* rather than replaced — `REPLACE` overwrites the whole row, including them.
     */
    @Query("SELECT * FROM recently_played WHERE provider = :provider AND sourceId = :sourceId")
    suspend fun find(provider: String, sourceId: String): RecentlyPlayedEntity?

    @Query("DELETE FROM recently_played WHERE provider = :provider AND sourceId = :sourceId")
    suspend fun delete(provider: String, sourceId: String)

    @Query("SELECT * FROM recently_played ORDER BY playedAtIso DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<RecentlyPlayedEntity>>

    @Query("SELECT * FROM recently_played")
    suspend fun all(): List<RecentlyPlayedEntity>

    /**
     * Keeps the [keep] best rows, deleting the rest so the table stays bounded.
     *
     * "Best" is **most-played first, then most-recent** — not recency alone. Pruning by recency would
     * throw away exactly the material "Rediscover" is made of: a record played twenty times last spring
     * matters more than a track opened once yesterday and never finished.
     */
    @Query(
        """
        DELETE FROM recently_played
        WHERE (provider || ':' || sourceId) NOT IN (
            SELECT provider || ':' || sourceId FROM recently_played
            ORDER BY playCount DESC, playedAtIso DESC LIMIT :keep
        )
        """,
    )
    suspend fun prune(keep: Int)

    @Query("DELETE FROM recently_played")
    suspend fun clear()
}
