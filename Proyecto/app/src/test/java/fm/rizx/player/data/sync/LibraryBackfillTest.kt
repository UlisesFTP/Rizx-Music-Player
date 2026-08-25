package fm.rizx.player.data.sync

import fm.rizx.player.data.local.db.FavoriteEntity
import fm.rizx.player.data.local.db.InMemoryFavoriteDao
import fm.rizx.player.data.local.db.InMemoryOutbox
import fm.rizx.player.data.local.db.InMemoryPlaylistDao
import fm.rizx.player.data.local.db.InMemoryRecentlyPlayedDao
import fm.rizx.player.data.local.db.InMemoryTasteContributionDao
import fm.rizx.player.data.local.db.PlaylistEntity
import fm.rizx.player.data.local.db.RecentlyPlayedEntity
import fm.rizx.player.data.local.db.SyncOutboxEntity
import fm.rizx.player.data.local.db.TasteContributionEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBackfillTest {

    private val outbox = InMemoryOutbox()
    private val playlists = InMemoryPlaylistDao(outbox)
    private val favorites = InMemoryFavoriteDao(outbox)
    private val recents = InMemoryRecentlyPlayedDao(outbox)
    private val contributions = InMemoryTasteContributionDao()
    private var ids = 0
    private val backfill = LibraryBackfill(playlists, favorites, recents, contributions, newId = { "op${ids++}" }, nowIso = { "now" })

    private suspend fun seed() {
        playlists.insertPlaylist(PlaylistEntity("p1", "One", null, "t", "t", false, null, null, null))
        playlists.insertPlaylist(PlaylistEntity("p2", "Two", null, "t", "t", false, null, null, null))
        favorites.insert(FavoriteEntity("TRACK", "deezer", "1", "{}", "t"))
        recents.upsert(RecentlyPlayedEntity("deezer", "1", "{}", "t", playCount = 2))
        recents.upsert(RecentlyPlayedEntity("deezer", "2", "{}", "t", playCount = 1))
    }

    private fun keys(): List<Pair<String, String>> = outbox.rows.value.map { it.entityType to it.entityId }

    @Test
    fun `queues every playlist, favorite and listening row, plus a retirement for the old taste key`() = runTest {
        seed()

        assertEquals(5, backfill.journalEverything())

        assertEquals(
            setOf(
                "PLAYLIST" to "p1", "PLAYLIST" to "p2",
                "FAVORITE" to "TRACK:deezer:1",
                "TASTE" to "deezer:1", "TASTE" to "legacy|deezer:1",
                "TASTE" to "deezer:2", "TASTE" to "legacy|deezer:2",
            ),
            keys().toSet(),
        )
        val legacy = outbox.rows.value.filter { it.entityId.startsWith(TasteKeys.LEGACY_PREFIX) }
        assertTrue(legacy.all { it.operation == "DELETE" && it.payloadJson == null })
    }

    @Test
    fun `running it again leaves one operation per entity`() = runTest {
        seed()
        recents.insertSyncOperation(SyncOutboxEntity("older", "TASTE", "deezer:1", "UPSERT", "{}", "before"))

        backfill.journalEverything()
        backfill.journalEverything()

        assertEquals(7, outbox.rows.value.size)
        assertEquals(1, outbox.rows.value.count { it.entityType == "TASTE" && it.entityId == "deezer:1" })
    }

    @Test
    fun `knows whether there is a library to protect`() = runTest {
        assertFalse(backfill.hasLocalLibrary())
        recents.upsert(RecentlyPlayedEntity("deezer", "1", "{}", "t"))
        assertTrue(backfill.hasLocalLibrary())
    }

    @Test
    fun `wiping takes the library and other devices' taste, not the outbox`() = runTest {
        seed()
        contributions.upsert(TasteContributionEntity("dev-B", "deezer", "1", "{}", "t", playCount = 1))
        backfill.journalEverything()

        backfill.wipeLocalLibrary()

        assertFalse(backfill.hasLocalLibrary())
        assertTrue(contributions.all().isEmpty())
        assertEquals("the outbox is the scheduler's to clear", 7, outbox.rows.value.size)
    }
}
