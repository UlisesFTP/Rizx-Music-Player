package fm.rizx.player.data.local.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * In-memory stand-ins for the Room DAOs that touch sync, sharing one [InMemoryOutbox] the way the real
 * DAOs share the `sync_outbox` table. They mirror the SQL semantics the code relies on — `IGNORE` vs
 * `REPLACE`, prune order, coalescing by key — and nothing else.
 */
class InMemoryOutbox {
    val rows = MutableStateFlow<List<SyncOutboxEntity>>(emptyList())

    fun insert(operation: SyncOutboxEntity) {
        rows.value = rows.value + operation
    }

    fun deleteFor(entityType: String, entityId: String) {
        rows.value = rows.value.filterNot { it.entityType == entityType && it.entityId == entityId }
    }
}

class InMemorySyncDao(val outbox: InMemoryOutbox = InMemoryOutbox()) : SyncDao {
    val states = mutableMapOf<String, SyncStateEntity>()
    val recoveries = mutableListOf<SyncRecoveryEntity>()

    override fun observePendingCount(): Flow<Int> = outbox.rows.map { it.size }
    override suspend fun pendingCount(): Int = outbox.rows.value.size
    override suspend fun pendingCountExcluding(excludedType: String): Int = outbox.rows.value.count { it.entityType != excludedType }
    override suspend fun pending(limit: Int): List<SyncOutboxEntity> = outbox.rows.value.sortedBy { it.createdAtIso }.take(limit)
    override suspend fun pendingExcluding(excludedType: String, limit: Int): List<SyncOutboxEntity> =
        outbox.rows.value.filter { it.entityType != excludedType }.sortedBy { it.createdAtIso }.take(limit)
    override suspend fun pendingKeys(): List<SyncKey> = outbox.rows.value.map { SyncKey(it.entityType, it.entityId) }
    override suspend fun enqueue(operation: SyncOutboxEntity) {
        if (outbox.rows.value.none { it.operationId == operation.operationId }) outbox.insert(operation)
    }
    override suspend fun acknowledge(operationIds: List<String>) {
        outbox.rows.value = outbox.rows.value.filterNot { it.operationId in operationIds }
    }
    override suspend fun markAttempted(operationIds: List<String>) {
        outbox.rows.value = outbox.rows.value.map { if (it.operationId in operationIds) it.copy(attemptCount = it.attemptCount + 1) else it }
    }
    override suspend fun evictExhausted(maxAttempts: Int): Int {
        val before = outbox.rows.value.size
        outbox.rows.value = outbox.rows.value.filter { it.attemptCount < maxAttempts }
        return before - outbox.rows.value.size
    }
    override suspend fun clearOutbox() { outbox.rows.value = emptyList() }
    override suspend fun hasPending(entityType: String, entityId: String): Boolean =
        outbox.rows.value.any { it.entityType == entityType && it.entityId == entityId }
    override suspend fun state(accountId: String): SyncStateEntity? = states[accountId]
    override fun observeState(accountId: String): Flow<SyncStateEntity?> = outbox.rows.map { states[accountId] }
    override suspend fun anyDeviceId(): String? = states.values.firstOrNull()?.deviceId
    override suspend fun saveState(state: SyncStateEntity) { states[state.accountId] = state }
    override suspend fun saveRecovery(snapshot: SyncRecoveryEntity) { recoveries += snapshot }
    override suspend fun pruneRecovery(nowIso: String) { recoveries.removeAll { it.expiresAtIso < nowIso } }
}

class InMemoryTasteContributionDao : TasteContributionDao {
    val rows = MutableStateFlow<Map<String, TasteContributionEntity>>(emptyMap())
    private fun key(d: String, p: String, s: String) = "$d|$p:$s"

    override suspend fun upsert(row: TasteContributionEntity) {
        rows.value = rows.value + (key(row.deviceId, row.provider, row.sourceId) to row)
    }
    override suspend fun delete(deviceId: String, provider: String, sourceId: String) {
        rows.value = rows.value - key(deviceId, provider, sourceId)
    }
    override suspend fun deleteDevice(deviceId: String) { rows.value = rows.value.filterValues { it.deviceId != deviceId } }
    override fun observeAll(): Flow<List<TasteContributionEntity>> = rows.map { it.values.toList() }
    override suspend fun all(): List<TasteContributionEntity> = rows.value.values.toList()
    override suspend fun prune(deviceId: String, keep: Int) {
        val kept = rows.value.values.filter { it.deviceId == deviceId }
            .sortedWith(compareByDescending<TasteContributionEntity> { it.playCount }.thenByDescending { it.playedAtIso })
            .take(keep).map { key(it.deviceId, it.provider, it.sourceId) }.toSet()
        rows.value = rows.value.filter { (k, v) -> v.deviceId != deviceId || k in kept }
    }
    override suspend fun clear() { rows.value = emptyMap() }
}

class InMemoryFavoriteDao(val outbox: InMemoryOutbox = InMemoryOutbox()) : FavoriteDao {
    val rows = MutableStateFlow<List<FavoriteEntity>>(emptyList())
    private fun matches(e: FavoriteEntity, type: String, provider: String, sourceId: String) =
        e.type == type && e.provider == provider && e.sourceId == sourceId

    override suspend fun insert(entity: FavoriteEntity) {
        if (rows.value.none { matches(it, entity.type, entity.provider, entity.sourceId) }) rows.value = rows.value + entity
    }
    override suspend fun upsertFromSync(entity: FavoriteEntity) {
        rows.value = rows.value.filterNot { matches(it, entity.type, entity.provider, entity.sourceId) } + entity
    }
    override suspend fun deleteSyncOperationsFor(entityType: String, entityId: String) = outbox.deleteFor(entityType, entityId)
    override suspend fun insertSyncOperationRow(operation: SyncOutboxEntity) = outbox.insert(operation)
    override suspend fun delete(type: String, provider: String, sourceId: String) {
        rows.value = rows.value.filterNot { matches(it, type, provider, sourceId) }
    }
    override suspend fun find(type: String, provider: String, sourceId: String): FavoriteEntity? =
        rows.value.firstOrNull { matches(it, type, provider, sourceId) }
    override suspend fun all(): List<FavoriteEntity> = rows.value
    override suspend fun deleteAll() { rows.value = emptyList() }
    override fun observeByType(type: String): Flow<List<FavoriteEntity>> =
        rows.map { list -> list.filter { it.type == type }.sortedByDescending { it.addedAtIso } }
    override fun observeExists(type: String, provider: String, sourceId: String): Flow<Boolean> =
        rows.map { list -> list.any { matches(it, type, provider, sourceId) } }
    override suspend fun exists(type: String, provider: String, sourceId: String): Boolean =
        rows.value.any { matches(it, type, provider, sourceId) }
}

class InMemoryRecentlyPlayedDao(val outbox: InMemoryOutbox = InMemoryOutbox()) : RecentlyPlayedDao {
    val rows = MutableStateFlow<Map<String, RecentlyPlayedEntity>>(emptyMap())
    private fun key(e: RecentlyPlayedEntity) = "${e.provider}:${e.sourceId}"

    override suspend fun upsert(entry: RecentlyPlayedEntity) { rows.value = rows.value + (key(entry) to entry) }
    override suspend fun deleteSyncOperationsFor(entityType: String, entityId: String) = outbox.deleteFor(entityType, entityId)
    override suspend fun insertSyncOperationRow(operation: SyncOutboxEntity) = outbox.insert(operation)
    override suspend fun find(provider: String, sourceId: String): RecentlyPlayedEntity? = rows.value["$provider:$sourceId"]
    override suspend fun delete(provider: String, sourceId: String) { rows.value = rows.value - "$provider:$sourceId" }
    override fun observe(limit: Int): Flow<List<RecentlyPlayedEntity>> =
        rows.map { m -> m.values.sortedByDescending { it.playedAtIso }.take(limit) }
    override suspend fun all(): List<RecentlyPlayedEntity> = rows.value.values.toList()
    override suspend fun prune(keep: Int) {
        val kept = rows.value.values
            .sortedWith(compareByDescending<RecentlyPlayedEntity> { it.playCount }.thenByDescending { it.playedAtIso })
            .take(keep).map { key(it) }.toSet()
        rows.value = rows.value.filterKeys { it in kept }
    }
    override suspend fun clear() { rows.value = emptyMap() }
}

class InMemoryPlaylistDao(val outbox: InMemoryOutbox = InMemoryOutbox()) : PlaylistDao {
    val playlists = MutableStateFlow<Map<String, PlaylistEntity>>(emptyMap())
    val items = MutableStateFlow<List<PlaylistItemEntity>>(emptyList())

    override suspend fun insertPlaylist(playlist: PlaylistEntity) { playlists.value = playlists.value + (playlist.id to playlist) }
    override suspend fun insertItems(items: List<PlaylistItemEntity>) { this.items.value += items }
    override suspend fun deleteSyncOperationsFor(entityType: String, entityId: String) = outbox.deleteFor(entityType, entityId)
    override suspend fun insertSyncOperationRow(operation: SyncOutboxEntity) = outbox.insert(operation)
    override suspend fun allIds(): List<String> = playlists.value.keys.toList()
    override suspend fun deleteAll() { playlists.value = emptyMap(); items.value = emptyList() }
    override suspend fun updatePlaylist(playlist: PlaylistEntity) { playlists.value = playlists.value + (playlist.id to playlist) }
    override suspend fun deletePlaylist(id: String) {
        playlists.value = playlists.value - id
        items.value = items.value.filterNot { it.playlistId == id }
    }
    override suspend fun getPlaylist(id: String): PlaylistEntity? = playlists.value[id]
    override fun observePlaylist(id: String): Flow<PlaylistEntity?> = playlists.map { it[id] }
    override fun observeSummaries(): Flow<List<PlaylistSummaryRow>> = combine(playlists, items) { pls, its ->
        pls.values.map { p -> PlaylistSummaryRow(p.id, p.name, p.description, p.isReadOnly, its.count { it.playlistId == p.id }, p.artworkUrl) }
    }
    override suspend fun setArtworkUrl(id: String, url: String?) {
        playlists.value[id]?.let { playlists.value = playlists.value + (id to it.copy(artworkUrl = url)) }
    }
    override suspend fun insertItem(item: PlaylistItemEntity) { items.value = items.value + item }
    override suspend fun deleteItem(itemId: String) { items.value = items.value.filterNot { it.id == itemId } }
    override suspend fun deleteItems(playlistId: String) { items.value = items.value.filterNot { it.playlistId == playlistId } }
    override fun observeItems(playlistId: String): Flow<List<PlaylistItemEntity>> =
        items.map { list -> list.filter { it.playlistId == playlistId }.sortedBy { it.sortOrder } }
    override fun observeItemDigests(): Flow<List<PlaylistItemDigestRow>> =
        items.map { list -> list.sortedWith(compareBy({ it.playlistId }, { it.sortOrder })).map { PlaylistItemDigestRow(it.playlistId, it.trackJson) } }
    override suspend fun getItems(playlistId: String): List<PlaylistItemEntity> =
        items.value.filter { it.playlistId == playlistId }.sortedBy { it.sortOrder }
    override suspend fun updateItemTrack(itemId: String, trackJson: String) {
        items.value = items.value.map { if (it.id == itemId) it.copy(trackJson = trackJson) else it }
    }
    override suspend fun updateOrder(itemId: String, order: Int) {
        items.value = items.value.map { if (it.id == itemId) it.copy(sortOrder = order) else it }
    }
    override suspend fun maxOrder(playlistId: String): Int =
        items.value.filter { it.playlistId == playlistId }.maxOfOrNull { it.sortOrder } ?: -1
}
