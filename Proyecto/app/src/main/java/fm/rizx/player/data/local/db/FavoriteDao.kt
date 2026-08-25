package fm.rizx.player.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    /** Idempotent add: an existing `(type, provider, sourceId)` row is kept (its `addedAtIso` too). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: FavoriteEntity)

    /**
     * The cloud's version of a favorite replaces the local row. [insert]'s `IGNORE` is right for a
     * local re-like, and wrong here: it silently dropped every remote edit to a key already present.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFromSync(entity: FavoriteEntity)

    @Query("DELETE FROM sync_outbox WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun deleteSyncOperationsFor(entityType: String, entityId: String)

    @Insert
    suspend fun insertSyncOperationRow(operation: SyncOutboxEntity)

    /** One pending operation per favorite: like-unlike-like leaves a single UPSERT, not three rows. */
    @Transaction
    suspend fun insertSyncOperation(operation: SyncOutboxEntity) {
        deleteSyncOperationsFor(operation.entityType, operation.entityId)
        insertSyncOperationRow(operation)
    }

    @Transaction
    suspend fun insertWithJournal(entity: FavoriteEntity, operation: SyncOutboxEntity) {
        insert(entity)
        insertSyncOperation(operation)
    }

    @Query("DELETE FROM favorites WHERE type = :type AND provider = :provider AND sourceId = :sourceId")
    suspend fun delete(type: String, provider: String, sourceId: String)

    @Transaction
    suspend fun deleteWithJournal(
        type: String,
        provider: String,
        sourceId: String,
        operation: SyncOutboxEntity,
    ) {
        delete(type, provider, sourceId)
        insertSyncOperation(operation)
    }

    @Query("SELECT * FROM favorites WHERE type = :type AND provider = :provider AND sourceId = :sourceId")
    suspend fun find(type: String, provider: String, sourceId: String): FavoriteEntity?

    @Query("SELECT * FROM favorites")
    suspend fun all(): List<FavoriteEntity>

    @Query("DELETE FROM favorites")
    suspend fun deleteAll()

    @Query("SELECT * FROM favorites WHERE type = :type ORDER BY addedAtIso DESC")
    fun observeByType(type: String): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE type = :type AND provider = :provider AND sourceId = :sourceId)")
    fun observeExists(type: String, provider: String, sourceId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE type = :type AND provider = :provider AND sourceId = :sourceId)")
    suspend fun exists(type: String, provider: String, sourceId: String): Boolean
}
