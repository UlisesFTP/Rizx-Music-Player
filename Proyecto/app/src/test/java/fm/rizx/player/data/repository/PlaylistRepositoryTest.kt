package fm.rizx.player.data.repository

import fm.rizx.player.data.local.db.PlaylistDao
import fm.rizx.player.data.local.db.PlaylistEntity
import fm.rizx.player.data.local.db.PlaylistItemEntity
import fm.rizx.player.data.local.db.PlaylistSummaryRow
import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.domain.model.PlaylistPreview
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.EnabledProviderStore
import fm.rizx.player.domain.provider.PlaylistProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.repository.ReadOnlyPlaylistException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRepositoryTest {

    private class FakePlaylistDao : PlaylistDao {
        val playlists = MutableStateFlow<Map<String, PlaylistEntity>>(emptyMap())
        val items = MutableStateFlow<List<PlaylistItemEntity>>(emptyList())

        override suspend fun insertPlaylist(playlist: PlaylistEntity) {
            playlists.value = playlists.value + (playlist.id to playlist)
        }
        override suspend fun updatePlaylist(playlist: PlaylistEntity) {
            playlists.value = playlists.value + (playlist.id to playlist)
        }
        override suspend fun deletePlaylist(id: String) {
            playlists.value = playlists.value - id
            items.value = items.value.filterNot { it.playlistId == id }
        }
        override suspend fun getPlaylist(id: String): PlaylistEntity? = playlists.value[id]
        override fun observePlaylist(id: String): Flow<PlaylistEntity?> = playlists.map { it[id] }
        override fun observeSummaries(): Flow<List<PlaylistSummaryRow>> =
            combine(playlists, items) { pls, its ->
                pls.values.map { p ->
                    PlaylistSummaryRow(
                        p.id, p.name, p.description, p.isReadOnly,
                        its.count { it.playlistId == p.id }, p.artworkUrl,
                    )
                }
            }
        override suspend fun setArtworkUrl(id: String, url: String?) {
            playlists.value[id]?.let { playlists.value = playlists.value + (id to it.copy(artworkUrl = url)) }
        }
        override suspend fun updateItemTrack(itemId: String, trackJson: String) {
            items.value = items.value.map { if (it.id == itemId) it.copy(trackJson = trackJson) else it }
        }
        override suspend fun insertItem(item: PlaylistItemEntity) { items.value = items.value + item }
        override suspend fun deleteItem(itemId: String) { items.value = items.value.filterNot { it.id == itemId } }
        override fun observeItems(playlistId: String): Flow<List<PlaylistItemEntity>> =
            items.map { list -> list.filter { it.playlistId == playlistId }.sortedBy { it.sortOrder } }
        override suspend fun getItems(playlistId: String): List<PlaylistItemEntity> =
            items.value.filter { it.playlistId == playlistId }.sortedBy { it.sortOrder }
        override suspend fun updateOrder(itemId: String, order: Int) {
            items.value = items.value.map { if (it.id == itemId) it.copy(sortOrder = order) else it }
        }
        override suspend fun maxOrder(playlistId: String): Int =
            items.value.filter { it.playlistId == playlistId }.maxOfOrNull { it.sortOrder } ?: -1
    }

    private var ids = 0
    private fun repo(dao: PlaylistDao) = PlaylistRepositoryImpl(dao, newId = { "id${ids++}" }, nowIso = { "t" })

    private fun track(title: String) = Track(title = title, source = ProviderRef("meta", "tr-$title"))

    @Test
    fun `the same track added twice gets distinct playlist item ids`() = runTest {
        val dao = FakePlaylistDao()
        val repo = repo(dao)
        val id = repo.createPlaylist("Mix")
        val song = track("Velvet")

        repo.addTracks(id, listOf(song, song))

        val items = repo.playlist(id).first()!!.items
        assertEquals(2, items.size)
        assertNotEquals(items[0].id, items[1].id)
        assertEquals(items[0].track.source, items[1].track.source)
    }

    @Test
    fun `reorder moves an item to a new position`() = runTest {
        val dao = FakePlaylistDao()
        val repo = repo(dao)
        val id = repo.createPlaylist("Mix")
        repo.addTracks(id, listOf(track("A"), track("B"), track("C")))

        repo.reorder(id, fromIndex = 0, toIndex = 2)

        assertEquals(listOf("B", "C", "A"), repo.playlist(id).first()!!.items.map { it.track.title })
    }

    @Test
    fun `a read-only playlist rejects mutations`() = runTest {
        val dao = FakePlaylistDao()
        dao.insertPlaylist(
            PlaylistEntity("ro", "Imported", null, "t", "t", isReadOnly = true, parentId = null, originProvider = null, originId = null),
        )
        val repo = repo(dao)

        var threw = false
        try {
            repo.addTracks("ro", listOf(track("A")))
        } catch (e: ReadOnlyPlaylistException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `export then import round-trips, persisted under a fresh id`() = runTest {
        val dao = FakePlaylistDao()
        val repo = repo(dao)
        val id = repo.createPlaylist("Road Trip")
        repo.addTracks(id, listOf(track("A"), track("B")))

        val json = repo.exportPlaylist(id)!!
        val importedId = repo.importPlaylistFile(json)

        val imported = repo.playlist(importedId).first()!!
        assertEquals("Road Trip", imported.name)
        assertEquals(listOf("A", "B"), imported.items.map { it.track.title })
        assertNotEquals(importedId, id) // the URL/file is not identity — imports get a fresh local id
    }

    @Test
    fun `an imported playlist is editable`() = runTest {
        val dao = FakePlaylistDao()
        val repo = repo(dao)
        val src = repo.createPlaylist("Mix").also { repo.addTracks(it, listOf(track("A"))) }
        val importedId = repo.importPlaylistFile(repo.exportPlaylist(src)!!)

        repo.addTracks(importedId, listOf(track("B"))) // must not throw: imports are normal playlists

        val imported = repo.playlist(importedId).first()!!
        assertFalse(imported.isReadOnly)
        assertEquals(listOf("A", "B"), imported.items.map { it.track.title })
    }

    private class FakePlaylistProvider(private val handles: Boolean, private val preview: PlaylistPreview) : PlaylistProvider {
        override val id = "fake-pl"
        override val kind = ProviderKind.PLAYLISTS
        override val name = "Fake Playlists"
        override fun canHandle(url: String) = handles
        override suspend fun fetchPlaylist(url: String) = preview
    }

    private class AllEnabled : EnabledProviderStore {
        override fun isEnabled(id: String) = flowOf(true)
        override suspend fun setEnabled(id: String, enabled: Boolean) {}
        override suspend fun snapshot(ids: Collection<String>) = ids.associateWith { true }
    }

    @Test
    fun `importFromUrl saves an editable playlist from the matching provider`() = runTest {
        val dao = FakePlaylistDao()
        val registry = DefaultProviderRegistry().apply {
            register(FakePlaylistProvider(handles = true, preview = PlaylistPreview("Top France", tracks = listOf(track("A"), track("B")))))
        }
        val repo = PlaylistRepositoryImpl(dao, registry, AllEnabled(), newId = { "id${ids++}" }, nowIso = { "t" })

        val id = repo.importFromUrl("https://deezer.com/playlist/1")

        val imported = repo.playlist(id).first()!!
        assertEquals("Top France", imported.name)
        assertFalse(imported.isReadOnly) // imports are persisted immediately as normal, editable playlists
        assertEquals(listOf("A", "B"), imported.items.map { it.track.title })
    }

    private class CapturingPlaylistProvider(
        private val preview: PlaylistPreview,
        private val handles: (String) -> Boolean = { true },
    ) : PlaylistProvider {
        override val id = "cap-pl"
        override val kind = ProviderKind.PLAYLISTS
        override val name = "Capturing"
        var fetchedUrl: String? = null
        override fun canHandle(url: String) = handles(url)
        override suspend fun fetchPlaylist(url: String): PlaylistPreview { fetchedUrl = url; return preview }
    }

    @Test
    fun `previewPlaylist rebuilds a youtube url, fetches tracks, and does not save`() = runTest {
        val dao = FakePlaylistDao()
        val provider = CapturingPlaylistProvider(
            PlaylistPreview("YT Mix", tracks = listOf(track("A"), track("B"))),
            handles = { "youtube.com/playlist?list=" in it },
        )
        val repo = PlaylistRepositoryImpl(
            dao, DefaultProviderRegistry().apply { register(provider) }, AllEnabled(),
            newId = { "id${ids++}" }, nowIso = { "t" },
        )

        val tracks = repo.previewPlaylist(ProviderRef("youtube", "playlist:PL123"))

        assertEquals(listOf("A", "B"), tracks.map { it.title })
        assertEquals("https://www.youtube.com/playlist?list=PL123", provider.fetchedUrl) // reconstructed from the ref
        assertTrue(dao.playlists.value.isEmpty()) // preview must NOT persist anything
    }

    @Test
    fun `previewPlaylist rebuilds a deezer url from the namespaced id`() = runTest {
        val provider = CapturingPlaylistProvider(PlaylistPreview("Chart", tracks = listOf(track("A"))))
        val repo = PlaylistRepositoryImpl(
            FakePlaylistDao(), DefaultProviderRegistry().apply { register(provider) }, AllEnabled(),
            newId = { "id${ids++}" }, nowIso = { "t" },
        )

        repo.previewPlaylist(ProviderRef("deezer", "playlist:123"))

        assertEquals("https://www.deezer.com/playlist/123", provider.fetchedUrl)
    }

    @Test
    fun `importFromUrl fails when no provider can handle the url`() = runTest {
        val repo = PlaylistRepositoryImpl(
            FakePlaylistDao(),
            DefaultProviderRegistry().apply { register(FakePlaylistProvider(handles = false, preview = PlaylistPreview("x"))) },
            AllEnabled(),
            newId = { "id${ids++}" }, nowIso = { "t" },
        )

        var threw = false
        try {
            repo.importFromUrl("https://unknown/x")
        } catch (e: AppError.ProviderFailure) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `stored tracks carry no resolution state`() = runTest {
        val dao = FakePlaylistDao()
        val repo = repo(dao)
        val id = repo.createPlaylist("Mix")
        val resolved = track("Velvet").copy(
            streamCandidates = listOf(
                StreamCandidate(
                    id = "c1", title = "Velvet",
                    stream = Stream(url = "https://ephemeral/x.m4a", protocol = StreamProtocol.HTTPS, source = ProviderRef("s", "c1")),
                    source = ProviderRef("s", "c1"),
                ),
            ),
        )

        repo.addTracks(id, listOf(resolved))

        assertTrue(repo.playlist(id).first()!!.items.single().track.streamCandidates.isEmpty())
    }
}
