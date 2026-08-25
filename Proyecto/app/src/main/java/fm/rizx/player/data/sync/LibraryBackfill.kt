package fm.rizx.player.data.sync

import fm.rizx.player.data.local.db.FavoriteDao
import fm.rizx.player.data.local.db.FavoriteEntity
import fm.rizx.player.data.local.db.PlaylistDao
import fm.rizx.player.data.local.db.RecentlyPlayedDao
import fm.rizx.player.data.local.db.RecentlyPlayedEntity
import fm.rizx.player.data.local.db.SyncOutboxEntity
import fm.rizx.player.data.local.db.TasteContributionDao
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/** The whole local library, as the sync runner and scheduler need to see it. */
interface LibraryJournal {
    /** Queues an upsert for every playlist, favorite and listening row. Returns how many were queued. */
    suspend fun journalEverything(): Int

    /** Whether there is anything a different account could claim. */
    suspend fun hasLocalLibrary(): Boolean

    /** Playlists, favorites, history, other devices' taste and the outbox — gone. "Use the cloud copy". */
    suspend fun wipeLocalLibrary()
}

/**
 * Journals every local row so the next run pushes it.
 *
 * This is the one routine behind three moments: the first sign-in (local and cloud union), an install
 * whose taste rows were published under the old key (republished per device, old key retired), and
 * the explicit "merge" choice after switching accounts. Coalescing in the DAOs keeps it to one
 * operation per entity even when the outbox already holds newer edits.
 */
class LibraryBackfill(
    private val playlists: PlaylistDao,
    private val favorites: FavoriteDao,
    private val recents: RecentlyPlayedDao,
    private val contributions: TasteContributionDao,
    private val json: Json = Json { encodeDefaults = true },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val nowIso: () -> String = { Instant.now().toString() },
) : LibraryJournal {

    override suspend fun journalEverything(): Int {
        val now = nowIso()
        var count = 0
        playlists.allIds().forEach { id ->
            playlists.insertSyncOperation(SyncOutboxEntity(newId(), SyncDocuments.PLAYLIST, id, UPSERT, null, now))
            count++
        }
        favorites.all().forEach { row ->
            favorites.insertSyncOperation(
                SyncOutboxEntity(
                    newId(), SyncDocuments.FAVORITE, "${row.type}:${row.provider}:${row.sourceId}", UPSERT,
                    json.encodeToString(FavoriteEntity.serializer(), row), now,
                ),
            )
            count++
        }
        recents.all().forEach { row ->
            val key = "${row.provider}:${row.sourceId}"
            recents.insertSyncOperation(
                SyncOutboxEntity(newId(), SyncDocuments.TASTE, key, UPSERT, json.encodeToString(RecentlyPlayedEntity.serializer(), row), now),
            )
            // Retire the row this track had under the old, device-less key, so no reader adds it twice.
            recents.insertSyncOperation(SyncOutboxEntity(newId(), SyncDocuments.TASTE, TasteKeys.LEGACY_PREFIX + key, DELETE, null, now))
            count++
        }
        return count
    }

    override suspend fun hasLocalLibrary(): Boolean =
        playlists.allIds().isNotEmpty() || favorites.all().isNotEmpty() || recents.all().isNotEmpty()

    override suspend fun wipeLocalLibrary() {
        playlists.deleteAll()
        favorites.deleteAll()
        recents.clear()
        contributions.clear()
    }

    private companion object {
        const val UPSERT = "UPSERT"
        const val DELETE = "DELETE"
    }
}
