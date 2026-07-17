package fm.rizx.player.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    // ---- Playlists ----

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    /**
     * Updates a playlist in place. Must **not** be an `@Insert(REPLACE)`: REPLACE deletes the row and
     * re-inserts it, which would cascade-delete the playlist's items. `@Update` issues a real UPDATE.
     */
    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylist(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observePlaylist(id: String): Flow<PlaylistEntity?>

    @Query(
        """
        SELECT p.id AS id, p.name AS name, p.description AS description, p.isReadOnly AS isReadOnly,
               COUNT(i.id) AS itemCount
        FROM playlists p LEFT JOIN playlist_items i ON i.playlistId = p.id
        GROUP BY p.id
        ORDER BY p.lastModifiedIso DESC
        """,
    )
    fun observeSummaries(): Flow<List<PlaylistSummaryRow>>

    // ---- Items ----

    @Insert
    suspend fun insertItem(item: PlaylistItemEntity)

    @Query("DELETE FROM playlist_items WHERE id = :itemId")
    suspend fun deleteItem(itemId: String)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY sortOrder ASC")
    fun observeItems(playlistId: String): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY sortOrder ASC")
    suspend fun getItems(playlistId: String): List<PlaylistItemEntity>

    @Query("UPDATE playlist_items SET sortOrder = :order WHERE id = :itemId")
    suspend fun updateOrder(itemId: String, order: Int)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun maxOrder(playlistId: String): Int
}
