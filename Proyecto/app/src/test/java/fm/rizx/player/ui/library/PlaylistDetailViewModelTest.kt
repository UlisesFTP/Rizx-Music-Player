package fm.rizx.player.ui.library

import fm.rizx.player.NoDownloads
import androidx.lifecycle.SavedStateHandle
import fm.rizx.player.MainDispatcherRule
import fm.rizx.player.data.repository.InMemoryQueueRepository
import fm.rizx.player.domain.model.Playlist
import fm.rizx.player.domain.model.PlaylistItem
import fm.rizx.player.domain.model.PlaylistSummary
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.playback.PlaybackState
import fm.rizx.player.domain.repository.PlaylistRepository
import fm.rizx.player.domain.repository.PlaylistExportArtifact
import fm.rizx.player.domain.repository.PlaylistExportFormat
import fm.rizx.player.domain.repository.PlaylistExportRepository
import fm.rizx.player.domain.share.PlaylistShare
import fm.rizx.player.domain.share.PlaylistShareRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakePlaylists(private val stored: Playlist) : PlaylistRepository {
        val removed = mutableListOf<Pair<String, String>>()
        val deleted = mutableListOf<String>()
        override fun playlists(): Flow<List<PlaylistSummary>> = flowOf(emptyList())
        override fun playlist(id: String): Flow<Playlist?> = flowOf(if (id == stored.id) stored else null)
        override suspend fun createPlaylist(name: String, description: String?): String = "id"
        override suspend fun deletePlaylist(id: String) { deleted += id }
        override suspend fun rename(id: String, name: String, description: String?) {}
        override suspend fun addTracks(playlistId: String, tracks: List<Track>) {}
        override suspend fun removeItem(playlistId: String, itemId: String) { removed += playlistId to itemId }
        override suspend fun reorder(playlistId: String, fromIndex: Int, toIndex: Int) {}
        override suspend fun saveQueueAsPlaylist(name: String, tracks: List<Track>): String = "id"
        override suspend fun exportPlaylist(id: String): String? = null
        override suspend fun importPlaylistFile(text: String, fallbackName: String?): String = "id"
        override suspend fun importFromUrl(url: String): String = "id"
        override suspend fun previewPlaylist(source: ProviderRef): List<Track> = emptyList()
        override suspend fun backfillArtwork(id: String) = Unit
    }

    private class FakePlayback : PlaybackController {
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
        override fun playQueueItem(queueItemId: String) {}
        override fun playTrack(track: Track) {}
        var lastContextTracks: List<Track> = emptyList()
        var lastContextIndex: Int = -1
        override fun playContext(tracks: List<Track>, startIndex: Int, context: fm.rizx.player.domain.model.QueueContext) {
            lastContextTracks = tracks; lastContextIndex = startIndex
        }
        override fun playRadio(track: Track) {}
        override fun play() {}
        override fun pause() {}
        override fun toggle() {}
        override fun stop() {}
        override fun seekTo(positionMs: Long) {}
        override fun skipNext() {}
        override fun skipPrevious() {}
        override fun release() {}
    }

    private object NoExports : PlaylistExportRepository {
        override suspend fun export(playlistId: String, format: PlaylistExportFormat): PlaylistExportArtifact? = null
    }

    private object NoShares : PlaylistShareRepository {
        override val configured = false
        override suspend fun create(playlistId: String, captchaToken: String?) = PlaylistShare("", "", "")
        override suspend fun existing(playlistId: String): PlaylistShare? = null
        override suspend fun active(): List<PlaylistShare> = emptyList()
        override suspend fun revoke(shareId: String) = Unit
    }

    /** Answers without suspending, which is also the shape that would catch an init-order mistake. */
    private class ExistingShare(private val share: PlaylistShare?) : PlaylistShareRepository {
        override val configured = true
        var lookups = 0
            private set

        override suspend fun create(playlistId: String, captchaToken: String?) = PlaylistShare("", "", "")
        override suspend fun existing(playlistId: String): PlaylistShare? {
            lookups++
            return share
        }

        override suspend fun active(): List<PlaylistShare> = emptyList()
        override suspend fun revoke(shareId: String) = Unit
    }

    private class FailingShares : PlaylistShareRepository {
        override val configured = true
        override suspend fun create(playlistId: String, captchaToken: String?) = PlaylistShare("", "", "")
        override suspend fun existing(playlistId: String): PlaylistShare? = error("offline")
        override suspend fun active(): List<PlaylistShare> = emptyList()
        override suspend fun revoke(shareId: String) = Unit
    }

    private fun samplePlaylist() = Playlist(
        id = "p1", name = "Mix", createdAtIso = "t", lastModifiedIso = "t",
        items = listOf(PlaylistItem("i1", Track(title = "A", source = ProviderRef("meta", "a")), addedAtIso = "t")),
    )

    private fun threeTrackPlaylist() = Playlist(
        id = "p1", name = "Mix", createdAtIso = "t", lastModifiedIso = "t",
        items = listOf("A", "B", "C").mapIndexed { index, title ->
            PlaylistItem("i$index", Track(title = title, source = ProviderRef("meta", title)), addedAtIso = "t")
        },
    )

    private fun vm(
        repo: PlaylistRepository,
        playback: PlaybackController = FakePlayback(),
        shares: PlaylistShareRepository = NoShares,
    ) = PlaylistDetailViewModel(
        SavedStateHandle(mapOf("playlistId" to "p1")), repo, InMemoryQueueRepository(), playback, NoDownloads(),
        NoExports, shares,
    )

    @Test
    fun `exposes the playlist for its id`() = runTest {
        val playlist = samplePlaylist()

        val observed = vm(FakePlaylists(playlist)).playlist.first { it != null }

        assertEquals("Mix", observed?.name)
        assertEquals(listOf("i1"), observed?.items?.map { it.id })
    }

    @Test
    fun `removeItem delegates to the repository`() = runTest {
        val repo = FakePlaylists(samplePlaylist())

        vm(repo).removeItem("i1")
        advanceUntilIdle()

        assertEquals(listOf("p1" to "i1"), repo.removed)
    }

    @Test
    fun `play queues the whole playlist by default`() = runTest {
        val playback = FakePlayback()
        val repo = FakePlaylists(threeTrackPlaylist())
        val vm = vm(repo, playback)
        vm.playlist.first { it != null }

        vm.play(2)

        assertEquals(listOf("A", "B", "C"), playback.lastContextTracks.map { it.title })
        assertEquals(2, playback.lastContextIndex)
    }

    @Test
    fun `play queues only the rows the filter left on screen`() = runTest {
        val playback = FakePlayback()
        val stored = threeTrackPlaylist()
        val vm = vm(FakePlaylists(stored), playback)
        val playlist = vm.playlist.first { it != null }!!

        // Rows 2 and 3 survived the filter; tapping the first of them plays index 0 of *that* list.
        val visible = playlist.items.drop(1).map { it.track }
        vm.play(0, visible)

        assertEquals(listOf("B", "C"), playback.lastContextTracks.map { it.title })
        assertEquals(0, playback.lastContextIndex)
    }

    @Test
    fun `play does nothing when the filter excluded everything`() = runTest {
        val playback = FakePlayback()
        val vm = vm(FakePlaylists(threeTrackPlaylist()), playback)
        vm.playlist.first { it != null }

        vm.play(0, emptyList())

        assertEquals(-1, playback.lastContextIndex)
    }

    @Test
    fun `delete removes the playlist and invokes the callback`() = runTest {
        val repo = FakePlaylists(samplePlaylist())
        var deleted = false

        vm(repo).delete(onDeleted = { deleted = true })
        advanceUntilIdle()

        assertEquals(listOf("p1"), repo.deleted)
        assertTrue(deleted)
    }

    @Test
    fun `an existing link is restored when the playlist opens, so it can be copied and revoked again`() = runTest {
        val link = PlaylistShare("share-1", "https://example.test/s/token", "2026-08-27T16:49:59Z")
        val shares = ExistingShare(link)

        val vm = vm(FakePlaylists(samplePlaylist()), shares = shares)
        advanceUntilIdle()

        assertEquals(link, vm.shareState.value.link)
        assertEquals(1, shares.lookups)
    }

    @Test
    fun `a playlist with no link still offers to create one`() = runTest {
        val vm = vm(FakePlaylists(samplePlaylist()), shares = ExistingShare(null))
        advanceUntilIdle()

        assertNull(vm.shareState.value.link)
    }

    @Test
    fun `a lookup that fails leaves the sheet usable instead of taking the screen down`() = runTest {
        val vm = vm(FakePlaylists(samplePlaylist()), shares = FailingShares())
        advanceUntilIdle()

        assertNull(vm.shareState.value.link)
    }
}
