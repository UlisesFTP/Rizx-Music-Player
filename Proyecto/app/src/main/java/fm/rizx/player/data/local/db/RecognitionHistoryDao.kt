package fm.rizx.player.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecognitionHistoryDao {

    /**
     * Records one identification.
     *
     * `ABORT` rather than `REPLACE`, because there is nothing to replace: every recognition carries a
     * freshly-generated id, so a conflict here would mean a bug rather than a repeat listen.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: RecognitionHistoryEntity)

    @Query("SELECT * FROM recognition_history ORDER BY recognizedAtIso DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<RecognitionHistoryEntity>>

    @Query("DELETE FROM recognition_history WHERE id = :id")
    suspend fun delete(id: String)

    /** Drops the oldest rows beyond [keep]. Recency alone is the right rule for a log of moments. */
    @Query(
        """
        DELETE FROM recognition_history
        WHERE id NOT IN (SELECT id FROM recognition_history ORDER BY recognizedAtIso DESC LIMIT :keep)
        """,
    )
    suspend fun prune(keep: Int)

    @Query("DELETE FROM recognition_history")
    suspend fun clear()
}
