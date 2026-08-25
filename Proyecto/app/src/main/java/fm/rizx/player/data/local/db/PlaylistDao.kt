package fm.rizx.player.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    // ---- Playlists ----

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Insert
    suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM sync_outbox WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun deleteSyncOperationsFor(entityType: String, entityId: String)

    @Insert
    suspend fun insertSyncOperationRow(operation: SyncOutboxEntity)

    /**
     * One pending operation per entity. Six edits to a playlist used to queue six full re-exports of
     * the same list; the server keys records by entity, so only the newest state matters.
     */
    @Transaction
    suspend fun insertSyncOperation(operation: SyncOutboxEntity) {
        deleteSyncOperationsFor(operation.entityType, operation.entityId)
        insertSyncOperationRow(operation)
    }

    @Query("SELECT id FROM playlists")
    suspend fun allIds(): List<String>

    /** Items go with them by cascade. */
    @Query("DELETE FROM playlists")
    suspend fun deleteAll()

    @Transaction
    suspend fun createWithJournal(playlist: PlaylistEntity, operation: SyncOutboxEntity) {
        insertPlaylist(playlist)
        insertSyncOperation(operation)
    }

    @Transaction
    suspend fun updateWithJournal(playlist: PlaylistEntity, operation: SyncOutboxEntity) {
        updatePlaylist(playlist)
        insertSyncOperation(operation)
    }

    @Transaction
    suspend fun deleteWithJournal(id: String, operation: SyncOutboxEntity) {
        insertSyncOperation(operation)
        deletePlaylist(id)
    }

    @Transaction
    suspend fun addItemsWithJournal(
        items: List<PlaylistItemEntity>,
        playlist: PlaylistEntity,
        operation: SyncOutboxEntity,
    ) {
        insertItems(items)
        updatePlaylist(playlist)
        insertSyncOperation(operation)
    }

    @Transaction
    suspend fun removeItemWithJournal(
        itemId: String,
        playlist: PlaylistEntity,
        operation: SyncOutboxEntity,
    ) {
        deleteItem(itemId)
        updatePlaylist(playlist)
        insertSyncOperation(operation)
    }

    @Transaction
    suspend fun reorderWithJournal(
        itemIds: List<String>,
        playlist: PlaylistEntity,
        operation: SyncOutboxEntity,
    ) {
        itemIds.forEachIndexed { index, id -> updateOrder(id, index) }
        updatePlaylist(playlist)
        insertSyncOperation(operation)
    }

    @Transaction
    suspend fun importWithJournal(
        playlist: PlaylistEntity,
        items: List<PlaylistItemEntity>,
        operation: SyncOutboxEntity,
    ) {
        insertPlaylist(playlist)
        insertItems(items)
        insertSyncOperation(operation)
    }

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
               p.artworkUrl AS artworkUrl, p.originProvider AS originProvider, COUNT(i.id) AS itemCount
        FROM playlists p LEFT JOIN playlist_items i ON i.playlistId = p.id
        GROUP BY p.id
        ORDER BY p.lastModifiedIso DESC
        """,
    )
    fun observeSummaries(): Flow<List<PlaylistSummaryRow>>

    /** Sets the cover without touching lastModifiedIso — artwork is a cache, not a user edit. */
    @Query("UPDATE playlists SET artworkUrl = :url WHERE id = :id")
    suspend fun setArtworkUrl(id: String, url: String?)

    // ---- Items ----

    @Insert
    suspend fun insertItem(item: PlaylistItemEntity)

    @Query("DELETE FROM playlist_items WHERE id = :itemId")
    suspend fun deleteItem(itemId: String)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun deleteItems(playlistId: String)

    @Transaction
    suspend fun replaceFromSync(playlist: PlaylistEntity, items: List<PlaylistItemEntity>) {
        val existing = getPlaylist(playlist.id)
        if (existing == null) insertPlaylist(playlist) else updatePlaylist(playlist)
        deleteItems(playlist.id)
        insertItems(items)
    }

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY sortOrder ASC")
    fun observeItems(playlistId: String): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY sortOrder ASC")
    suspend fun getItems(playlistId: String): List<PlaylistItemEntity>

    /** Rewrites a stored track in place — used to backfill cover art onto already-imported items. */
    @Query("UPDATE playlist_items SET trackJson = :trackJson WHERE id = :itemId")
    suspend fun updateItemTrack(itemId: String, trackJson: String)

    @Query("UPDATE playlist_items SET sortOrder = :order WHERE id = :itemId")
    suspend fun updateOrder(itemId: String, order: Int)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun maxOrder(playlistId: String): Int
}
