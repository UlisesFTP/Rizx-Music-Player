package fm.rizx.player.data.sync

import fm.rizx.player.data.local.db.PlaylistDao
import fm.rizx.player.data.local.db.FavoriteDao
import fm.rizx.player.data.local.db.FavoriteEntity
import fm.rizx.player.data.local.db.PlaylistEntity
import fm.rizx.player.data.local.db.PlaylistItemEntity
import fm.rizx.player.data.local.db.SyncDao
import fm.rizx.player.data.local.db.SyncRecoveryEntity
import fm.rizx.player.data.local.db.RecentlyPlayedDao
import fm.rizx.player.data.local.db.RecentlyPlayedEntity
import fm.rizx.player.data.local.store.PlaylistTransfer
import fm.rizx.player.data.local.store.TrackJson
import fm.rizx.player.data.remote.supabase.SyncChange
import fm.rizx.player.domain.repository.PlaylistExportFormat
import fm.rizx.player.domain.repository.PlaylistExportRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistSyncEngine @Inject constructor(
    private val playlists: PlaylistDao,
    private val favorites: FavoriteDao,
    private val recentlyPlayed: RecentlyPlayedDao,
    private val sync: SyncDao,
    private val exports: PlaylistExportRepository,
    private val json: Json,
) {
    suspend fun apply(change: SyncChange) {
        when (change.entityType) {
            "PLAYLIST" -> applyPlaylist(change)
            "FAVORITE" -> applyFavorite(change)
            "TASTE" -> applyTaste(change)
        }
    }

    private suspend fun applyPlaylist(change: SyncChange) {
        if (sync.hasPending(change.entityType, change.entityId)) {
            exports.export(change.entityId, PlaylistExportFormat.RIZX_JSON)?.let { current ->
                sync.saveRecovery(
                    SyncRecoveryEntity(
                        id = UUID.randomUUID().toString(),
                        playlistId = change.entityId,
                        snapshotJson = current.content,
                        expiresAtIso = Instant.now().plus(30, ChronoUnit.DAYS).toString(),
                    ),
                )
            }
        }
        if (change.deleted) {
            playlists.deletePlaylist(change.entityId)
            return
        }
        val document = change.document ?: return
        val export = PlaylistTransfer.decode(document.toString())
        val now = Instant.now().toString()
        val old = playlists.getPlaylist(change.entityId)
        val entity = PlaylistEntity(
            id = change.entityId,
            name = export.name,
            description = export.description,
            createdAtIso = old?.createdAtIso ?: now,
            lastModifiedIso = now,
            isReadOnly = false,
            parentId = null,
            originProvider = old?.originProvider,
            originId = old?.originId,
            artworkUrl = old?.artworkUrl,
        )
        val exportedItems = if (export.items.isNotEmpty()) export.items else export.tracks.map {
            fm.rizx.player.data.local.store.PlaylistExportItem(it)
        }
        val items = exportedItems.mapIndexed { index, item ->
            PlaylistItemEntity(
                id = UUID.randomUUID().toString(),
                playlistId = change.entityId,
                sortOrder = index,
                trackJson = TrackJson.encodeTrack(item.track),
                note = item.note,
                addedAtIso = item.addedAtIso ?: now,
            )
        }
        playlists.replaceFromSync(entity, items)
    }

    private suspend fun applyFavorite(change: SyncChange) {
        if (change.deleted) {
            val parts = change.entityId.split(':', limit = 3)
            if (parts.size == 3) favorites.delete(parts[0], parts[1], parts[2])
            return
        }
        val entity = change.document?.let {
            runCatching { json.decodeFromJsonElement(FavoriteEntity.serializer(), it) }.getOrNull()
        } ?: return
        favorites.insert(entity)
    }

    private suspend fun applyTaste(change: SyncChange) {
        if (change.deleted) {
            val parts = change.entityId.split(':', limit = 2)
            if (parts.size == 2) recentlyPlayed.delete(parts[0], parts[1])
            return
        }
        val remote = change.document?.let {
            runCatching { json.decodeFromJsonElement(RecentlyPlayedEntity.serializer(), it) }.getOrNull()
        } ?: return
        val local = recentlyPlayed.find(remote.provider, remote.sourceId)
        // Counters are cumulative. Taking the greater observed value is idempotent and avoids an echo
        // from two devices inflating plays every time the same revision is downloaded.
        recentlyPlayed.upsert(
            if (local == null) remote else remote.copy(
                playCount = maxOf(local.playCount, remote.playCount),
                completedCount = maxOf(local.completedCount, remote.completedCount),
                skipCount = maxOf(local.skipCount, remote.skipCount),
                msListened = maxOf(local.msListened, remote.msListened),
                partNight = maxOf(local.partNight, remote.partNight),
                partMorning = maxOf(local.partMorning, remote.partMorning),
                partAfternoon = maxOf(local.partAfternoon, remote.partAfternoon),
                partEvening = maxOf(local.partEvening, remote.partEvening),
                firstPlayedAtIso = listOf(local.firstPlayedAtIso, remote.firstPlayedAtIso)
                    .filter(String::isNotBlank).minOrNull().orEmpty(),
                playedAtIso = maxOf(local.playedAtIso, remote.playedAtIso),
            ),
        )
    }
}
