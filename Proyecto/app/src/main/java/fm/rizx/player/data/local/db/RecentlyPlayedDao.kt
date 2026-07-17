package fm.rizx.player.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentlyPlayedDao {

    /** Insert or bump: replaying a track replaces its row (new [RecentlyPlayedEntity.playedAtIso]). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RecentlyPlayedEntity)

    @Query("SELECT * FROM recently_played ORDER BY playedAtIso DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<RecentlyPlayedEntity>>

    /** Keeps the [keep] most-recent rows, deleting older ones so the table stays bounded. */
    @Query(
        """
        DELETE FROM recently_played
        WHERE (provider || ':' || sourceId) NOT IN (
            SELECT provider || ':' || sourceId FROM recently_played
            ORDER BY playedAtIso DESC LIMIT :keep
        )
        """,
    )
    suspend fun prune(keep: Int)

    @Query("DELETE FROM recently_played")
    suspend fun clear()
}
