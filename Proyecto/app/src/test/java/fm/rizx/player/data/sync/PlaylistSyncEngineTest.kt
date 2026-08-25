package fm.rizx.player.data.sync

import fm.rizx.player.data.local.db.FavoriteEntity
import fm.rizx.player.data.local.db.InMemoryFavoriteDao
import fm.rizx.player.data.local.db.InMemoryOutbox
import fm.rizx.player.data.local.db.InMemoryPlaylistDao
import fm.rizx.player.data.local.db.InMemoryRecentlyPlayedDao
import fm.rizx.player.data.local.db.InMemorySyncDao
import fm.rizx.player.data.local.db.InMemoryTasteContributionDao
import fm.rizx.player.data.local.db.PlaylistEntity
import fm.rizx.player.data.local.db.PlaylistItemEntity
import fm.rizx.player.data.local.db.RecentlyPlayedEntity
import fm.rizx.player.data.local.store.TrackJson
import fm.rizx.player.data.remote.supabase.SyncChange
import fm.rizx.player.data.repository.PlaylistExportRepositoryImpl
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.PlaylistExportFormat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistSyncEngineTest {

    private val outbox = InMemoryOutbox()
    private val playlists = InMemoryPlaylistDao(outbox)
    private val favorites = InMemoryFavoriteDao(outbox)
    private val recents = InMemoryRecentlyPlayedDao(outbox)
    private val contributions = InMemoryTasteContributionDao()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val exports = PlaylistExportRepositoryImpl(playlists)
    private val engine = PlaylistSyncEngine(playlists, favorites, recents, contributions, InMemorySyncDao(outbox), exports, json)

    private fun track(title: String) = Track(title = title, source = ProviderRef("deezer", "id-$title"))

    private suspend fun seedPlaylist(id: String = "p1", vararg titles: String) {
        playlists.insertPlaylist(PlaylistEntity(id, "Mix", null, "t0", "t0", false, null, null, null))
        playlists.insertItems(titles.mapIndexed { i, t -> PlaylistItemEntity("$id-item-$i", id, i, TrackJson.encodeTrack(track(t)), null, "t0") })
    }

    private suspend fun exported(id: String) = json.parseToJsonElement(exports.export(id, PlaylistExportFormat.RIZX_JSON)!!.content)

    @Test
    fun `a playlist that says what the local one says leaves item ids alone`() = runTest {
        seedPlaylist("p1", "A", "B")
        val document = exported("p1")

        assertNull(engine.apply(SyncChange(SyncDocuments.PLAYLIST, "p1", document, deleted = false, revision = 1)))

        assertEquals(listOf("p1-item-0", "p1-item-1"), playlists.getItems("p1").map { it.id })
    }

    @Test
    fun `a changed playlist replaces the local one`() = runTest {
        seedPlaylist("p1", "A", "B")
        val renamed = JsonObject((exported("p1") as JsonObject) + ("name" to JsonPrimitive("Renamed")))

        assertEquals(SyncAppliedKind.PLAYLIST, engine.apply(SyncChange(SyncDocuments.PLAYLIST, "p1", renamed, deleted = false, revision = 2)))

        assertEquals("Renamed", playlists.getPlaylist("p1")!!.name)
        assertEquals(2, playlists.getItems("p1").size)
    }

    @Test
    fun `a delete of something already gone changes nothing`() = runTest {
        assertNull(engine.apply(SyncChange(SyncDocuments.PLAYLIST, "nope", null, deleted = true, revision = 1)))
        assertNull(engine.apply(SyncChange(SyncDocuments.FAVORITE, "TRACK:deezer:nope", null, deleted = true, revision = 2)))
    }

    @Test
    fun `a remote favorite edit replaces the row, and the same one again is a no-op`() = runTest {
        favorites.insert(FavoriteEntity("TRACK", "deezer", "1", "old", "t0"))
        val remote = FavoriteEntity("TRACK", "deezer", "1", "new", "t1")
        val document = json.encodeToJsonElement(FavoriteEntity.serializer(), remote)

        assertEquals(SyncAppliedKind.FAVORITE, engine.apply(SyncChange(SyncDocuments.FAVORITE, "TRACK:deezer:1", document, false, 1)))
        assertEquals("new", favorites.find("TRACK", "deezer", "1")!!.json)
        assertNull(engine.apply(SyncChange(SyncDocuments.FAVORITE, "TRACK:deezer:1", document, false, 2)))
    }

    @Test
    fun `a legacy taste row counts only when this device has no row for the track`() = runTest {
        recents.upsert(RecentlyPlayedEntity("deezer", "1", "{}", "t5", playCount = 3))
        val document = json.encodeToJsonElement(RecentlyPlayedEntity.serializer(), RecentlyPlayedEntity("deezer", "1", "{}", "t1", playCount = 2))

        assertNull(engine.apply(SyncChange(SyncDocuments.TASTE, "deezer:1", document, false, 1)))
        assertEquals(0, contributions.all().size)

        val other = json.encodeToJsonElement(RecentlyPlayedEntity.serializer(), RecentlyPlayedEntity("deezer", "2", "{}", "t1", playCount = 2))
        assertEquals(SyncAppliedKind.TASTE, engine.apply(SyncChange(SyncDocuments.TASTE, "deezer:2", other, false, 2)))
        assertEquals(TasteKeys.LEGACY, contributions.all().single().deviceId)
    }

    @Test
    fun `another device's rows live under that device and stay bounded`() = runTest {
        repeat(301) { i ->
            val row = RecentlyPlayedEntity("deezer", "$i", "{}", "t${"%03d".format(i)}", playCount = i)
            engine.apply(SyncChange(SyncDocuments.TASTE, "dev-B|deezer:$i", json.encodeToJsonElement(RecentlyPlayedEntity.serializer(), row), false, i.toLong()))
        }

        val rows = contributions.all()
        assertEquals(300, rows.size)
        assertEquals(setOf("dev-B"), rows.map { it.deviceId }.toSet())
        assertNull("the least played row is the one pruned", rows.firstOrNull { it.sourceId == "0" })
    }

    @Test
    fun `a taste delete removes that device's row only`() = runTest {
        val row = RecentlyPlayedEntity("deezer", "1", "{}", "t1", playCount = 1)
        val document = json.encodeToJsonElement(RecentlyPlayedEntity.serializer(), row)
        engine.apply(SyncChange(SyncDocuments.TASTE, "dev-B|deezer:1", document, false, 1))
        engine.apply(SyncChange(SyncDocuments.TASTE, "dev-C|deezer:1", document, false, 2))

        assertEquals(SyncAppliedKind.TASTE, engine.apply(SyncChange(SyncDocuments.TASTE, "dev-B|deezer:1", null, true, 3)))

        assertEquals(listOf("dev-C"), contributions.all().map { it.deviceId })
    }
}
