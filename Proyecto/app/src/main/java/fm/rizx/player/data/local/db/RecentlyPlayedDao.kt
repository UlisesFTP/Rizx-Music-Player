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

    /**
     * The row for one track, or null. The repository reads it before writing so the v4 counters are
     * *summed* rather than replaced — `REPLACE` overwrites the whole row, including them.
     */
    @Query("SELECT * FROM recently_played WHERE provider = :provider AND sourceId = :sourceId")
    suspend fun find(provider: String, sourceId: String): RecentlyPlayedEntity?

    @Query("SELECT * FROM recently_played ORDER BY playedAtIso DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<RecentlyPlayedEntity>>

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
