package fm.rizx.player.ui.queue

import fm.rizx.player.data.repository.InMemoryQueueRepository
import fm.rizx.player.domain.model.Playlist
import fm.rizx.player.domain.model.PlaylistSummary
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.RepeatMode
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.PlaylistRepository
import fm.rizx.player.domain.repository.QueueRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueViewModelTest {

    private var counter = 0
    private fun repo(): QueueRepository = InMemoryQueueRepository(
        newId = { "q${counter++}" },
        nowIso = { "2026-01-01T00:00:00Z" },
    )

    /** The queue tests don't exercise playlist saving, so a no-op repository is enough. */
    private object NoopPlaylistRepository : PlaylistRepository {
        override fun playlists(): Flow<List<PlaylistSummary>> = flowOf(emptyList())
        override fun playlist(id: String): Flow<Playlist?> = flowOf(null)
        override suspend fun createPlaylist(name: String, description: String?): String = "id"
        override suspend fun deletePlaylist(id: String) {}
        override suspend fun rename(id: String, name: String, description: String?) {}
        override suspend fun addTracks(playlistId: String, tracks: List<Track>) {}
        override suspend fun removeItem(playlistId: String, itemId: String) {}
        override suspend fun reorder(playlistId: String, fromIndex: Int, toIndex: Int) {}
        override suspend fun saveQueueAsPlaylist(name: String, tracks: List<Track>): String = "id"
        override suspend fun exportPlaylist(id: String): String? = null
        override suspend fun importPlaylistFile(text: String, fallbackName: String?): String = "id"
        override suspend fun importFromUrl(url: String): String = "id"
        override suspend fun previewPlaylist(source: ProviderRef): List<Track> = emptyList()
        override suspend fun backfillArtwork(id: String) = Unit
    }

    private fun viewModel(repo: QueueRepository) = QueueViewModel(repo, NoopPlaylistRepository)

    private fun track(title: String) = Track(title = title, source = ProviderRef("fake", title))

    @Test
    fun `queue exposes the repository state`() {
        val repo = repo()
        val vm = viewModel(repo)
        repo.addToQueue(listOf(track("A"), track("B")))

        assertEquals(listOf("A", "B"), vm.queue.value.items.map { it.track.title })
    }

    @Test
    fun `playItem delegates to the repository`() {
        val repo = repo()
        repo.addToQueue(listOf(track("A"), track("B"), track("C")))
        val vm = viewModel(repo)
        val targetId = repo.state.value.items[2].id

        vm.playItem(targetId)

        assertEquals(2, repo.state.value.currentIndex)
    }

    @Test
    fun `removeItem delegates to the repository`() {
        val repo = repo()
        repo.addToQueue(listOf(track("A"), track("B")))
        val vm = viewModel(repo)
        val id = repo.state.value.items[0].id

        vm.removeItem(id)

        assertEquals(listOf("B"), repo.state.value.items.map { it.track.title })
    }

    @Test
    fun `cycleRepeatMode cycles OFF ALL ONE OFF`() {
        val repo = repo()
        val vm = viewModel(repo)

        assertEquals(RepeatMode.OFF, vm.queue.value.repeatMode)
        vm.cycleRepeatMode()
        assertEquals(RepeatMode.ALL, vm.queue.value.repeatMode)
        vm.cycleRepeatMode()
        assertEquals(RepeatMode.ONE, vm.queue.value.repeatMode)
        vm.cycleRepeatMode()
        assertEquals(RepeatMode.OFF, vm.queue.value.repeatMode)
    }

    @Test
    fun `clear empties the queue through the view model`() {
        val repo = repo()
        repo.addToQueue(listOf(track("A"), track("B")))
        val vm = viewModel(repo)

        vm.clear()

        assertTrue(vm.queue.value.items.isEmpty())
        assertEquals(-1, vm.queue.value.currentIndex)
    }

    @Test
    fun `addToQueue adds the selected track`() {
        val repo = repo()
        val vm = viewModel(repo)

        vm.addToQueue(track("Velvet Hours"))

        assertEquals(listOf("Velvet Hours"), vm.queue.value.items.map { it.track.title })
    }

    @Test
    fun `the same selected track can be added twice with distinct queue item ids`() {
        val repo = repo()
        val vm = viewModel(repo)
        val song = track("Goldenrod")

        vm.addToQueue(song)
        vm.addToQueue(song)

        val items = vm.queue.value.items
        assertEquals(2, items.size)
        // QueueItem id is per-insertion and differs from the track's ProviderRef id.
        assertNotEquals(items[0].id, items[1].id)
        assertNotEquals(song.source.id, items[0].id)
        assertEquals(items[0].track.source, items[1].track.source)
    }

    @Test
    fun `addNext inserts right after the current item`() {
        val repo = repo()
        repo.addToQueue(listOf(track("A"), track("B"))) // current = A (index 0)
        val vm = viewModel(repo)

        vm.addNext(track("X"))

        assertEquals(listOf("A", "X", "B"), vm.queue.value.items.map { it.track.title })
        assertEquals(0, vm.queue.value.currentIndex)
    }
}
