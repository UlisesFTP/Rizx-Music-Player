package fm.rizx.player.data.sync

import fm.rizx.player.data.local.db.FavoriteDao
import fm.rizx.player.data.local.db.FavoriteEntity
import fm.rizx.player.data.local.db.PlaylistDao
import fm.rizx.player.data.local.db.PlaylistEntity
import fm.rizx.player.data.local.db.PlaylistItemEntity
import fm.rizx.player.data.local.db.RecentlyPlayedDao
import fm.rizx.player.data.local.db.RecentlyPlayedEntity
import fm.rizx.player.data.local.db.SyncDao
import fm.rizx.player.data.local.db.SyncRecoveryEntity
import fm.rizx.player.data.local.db.TasteContributionDao
import fm.rizx.player.data.local.db.TasteContributionEntity
import fm.rizx.player.data.local.store.PlaylistTransfer
import fm.rizx.player.data.local.store.TrackJson
import fm.rizx.player.data.remote.supabase.SyncChange
import fm.rizx.player.domain.repository.PlaylistExportFormat
import fm.rizx.player.domain.repository.PlaylistExportRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncAppliedKind { PLAYLIST, FAVORITE, TASTE }

/** Writes cloud changes into the local library. Split from the runner so the runner can be tested alone. */
interface SyncApplier {
    /** Applies [change]; null when the library already said that, so nothing moved. */
    suspend fun apply(change: SyncChange): SyncAppliedKind?

    /** A remote playlist that lost to a dirty local one is kept for 30 days rather than dropped. */
    suspend fun keepRemoteSnapshot(playlistId: String, document: JsonElement)
}

/**
 * Applies remote records to Room. Every write goes through the DAOs, never the repositories, so nothing
 * applied here is journaled back into the outbox — that is what keeps a pull from echoing as a push.
 */
@Singleton
class PlaylistSyncEngine @Inject constructor(
    private val playlists: PlaylistDao,
    private val favorites: FavoriteDao,
    private val recentlyPlayed: RecentlyPlayedDao,
    private val contributions: TasteContributionDao,
    private val sync: SyncDao,
    private val exports: PlaylistExportRepository,
    private val json: Json,
) : SyncApplier {

    override suspend fun apply(change: SyncChange): SyncAppliedKind? = when (change.entityType) {
        SyncDocuments.PLAYLIST -> applyPlaylist(change)
        SyncDocuments.FAVORITE -> applyFavorite(change)
        SyncDocuments.TASTE -> applyTaste(change)
        else -> null
    }

    override suspend fun keepRemoteSnapshot(playlistId: String, document: JsonElement) {
        sync.saveRecovery(
            SyncRecoveryEntity(
                id = UUID.randomUUID().toString(),
                playlistId = playlistId,
                snapshotJson = document.toString(),
                expiresAtIso = Instant.now().plus(30, ChronoUnit.DAYS).toString(),
            ),
        )
    }

    private suspend fun applyPlaylist(change: SyncChange): SyncAppliedKind? {
        val old = playlists.getPlaylist(change.entityId)
        if (change.deleted) {
            if (old == null) return null
            playlists.deletePlaylist(change.entityId)
            return SyncAppliedKind.PLAYLIST
        }
        val document = change.document ?: return null
        // Same list already here: leave it alone. Replacing it would regenerate every item id for
        // nothing — and did, on every pull, because the server echoes what this device just pushed.
        val current = exports.export(change.entityId, PlaylistExportFormat.RIZX_JSON)?.content
            ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        if (current != null && SyncDocuments.same(SyncDocuments.PLAYLIST, current, document)) return null

        val export = PlaylistTransfer.decode(document.toString())
        val now = Instant.now().toString()
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
        return SyncAppliedKind.PLAYLIST
    }

    private suspend fun applyFavorite(change: SyncChange): SyncAppliedKind? {
        val parts = change.entityId.split(':', limit = 3)
        if (parts.size != 3) return null
        val existing = favorites.find(parts[0], parts[1], parts[2])
        if (change.deleted) {
            if (existing == null) return null
            favorites.delete(parts[0], parts[1], parts[2])
            return SyncAppliedKind.FAVORITE
        }
        val entity = change.document?.let {
            runCatching { json.decodeFromJsonElement(FavoriteEntity.serializer(), it) }.getOrNull()
        } ?: return null
        if (existing == entity) return null
        favorites.upsertFromSync(entity)
        return SyncAppliedKind.FAVORITE
    }

    /**
     * Another device's counters for a track, stored beside — never merged into — this device's own row.
     * The old un-prefixed rows are read as the pseudo-device "legacy", and only when this device has no
     * row for the track: when it does, the old `max` merge already folded them in, and counting them
     * again would be the double count the whole scheme exists to avoid.
     */
    private suspend fun applyTaste(change: SyncChange): SyncAppliedKind? {
        val key = TasteKeys.parse(change.entityId) ?: return null
        if (change.deleted) {
            contributions.delete(key.deviceId, key.provider, key.sourceId)
            return SyncAppliedKind.TASTE
        }
        val remote = change.document?.let {
            runCatching { json.decodeFromJsonElement(RecentlyPlayedEntity.serializer(), it) }.getOrNull()
        } ?: return null
        if (key.isLegacy && recentlyPlayed.find(key.provider, key.sourceId) != null) return null
        contributions.upsert(
            TasteContributionEntity(
                deviceId = key.deviceId,
                provider = key.provider,
                sourceId = key.sourceId,
                trackJson = remote.trackJson,
                playedAtIso = remote.playedAtIso,
                playCount = remote.playCount,
                completedCount = remote.completedCount,
                skipCount = remote.skipCount,
                msListened = remote.msListened,
                firstPlayedAtIso = remote.firstPlayedAtIso,
                partNight = remote.partNight,
                partMorning = remote.partMorning,
                partAfternoon = remote.partAfternoon,
                partEvening = remote.partEvening,
            ),
        )
        contributions.prune(key.deviceId, MAX_ROWS_PER_DEVICE)
        return SyncAppliedKind.TASTE
    }

    private companion object {
        /** Same bound as the local log (`RecentlyPlayedRepositoryImpl.MAX_ENTRIES`). */
        const val MAX_ROWS_PER_DEVICE = 300
    }
}
