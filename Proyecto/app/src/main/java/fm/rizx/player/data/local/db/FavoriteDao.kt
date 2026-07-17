package fm.rizx.player.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    /** Idempotent add: an existing `(type, provider, sourceId)` row is kept (its `addedAtIso` too). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE type = :type AND provider = :provider AND sourceId = :sourceId")
    suspend fun delete(type: String, provider: String, sourceId: String)

    @Query("SELECT * FROM favorites WHERE type = :type ORDER BY addedAtIso DESC")
    fun observeByType(type: String): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE type = :type AND provider = :provider AND sourceId = :sourceId)")
    fun observeExists(type: String, provider: String, sourceId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE type = :type AND provider = :provider AND sourceId = :sourceId)")
    suspend fun exists(type: String, provider: String, sourceId: String): Boolean
}
