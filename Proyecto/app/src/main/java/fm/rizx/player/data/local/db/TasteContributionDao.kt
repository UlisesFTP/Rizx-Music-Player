package fm.rizx.player.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Other devices' listening counters, one row per `(device, track)`.
 *
 * Kept apart from `recently_played` on purpose: that table is *this* device's own count and the only one
 * it ever writes to the cloud. Summing the two at read time is what makes taste additive across devices
 * without ever counting a play twice — a device only ever republishes its own row.
 */
@Dao
interface TasteContributionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TasteContributionEntity)

    @Query("DELETE FROM taste_contributions WHERE deviceId = :deviceId AND provider = :provider AND sourceId = :sourceId")
    suspend fun delete(deviceId: String, provider: String, sourceId: String)

    @Query("DELETE FROM taste_contributions WHERE deviceId = :deviceId")
    suspend fun deleteDevice(deviceId: String)

    @Query("SELECT * FROM taste_contributions")
    fun observeAll(): Flow<List<TasteContributionEntity>>

    @Query("SELECT * FROM taste_contributions")
    suspend fun all(): List<TasteContributionEntity>

    /** Same bound and the same "most-played, then most-recent" rule as `recently_played`, per device. */
    @Query(
        """
        DELETE FROM taste_contributions
        WHERE deviceId = :deviceId AND (provider || ':' || sourceId) NOT IN (
            SELECT provider || ':' || sourceId FROM taste_contributions
            WHERE deviceId = :deviceId
            ORDER BY playCount DESC, playedAtIso DESC LIMIT :keep
        )
        """,
    )
    suspend fun prune(deviceId: String, keep: Int)

    @Query("DELETE FROM taste_contributions")
    suspend fun clear()
}
