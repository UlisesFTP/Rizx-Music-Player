package fm.rizx.player.ui.detail

import fm.rizx.player.NoDownloads
import androidx.lifecycle.SavedStateHandle
import fm.rizx.player.MainDispatcherRule
import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.repository.InMemoryQueueRepository
import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.playback.PlaybackState
import fm.rizx.player.domain.repository.MetadataRepository
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeMeta(val album: (ProviderRef) -> Album?) : MetadataRepository {
        override suspend fun search(params: SearchParams) = SearchResults()
        override suspend fun albumDetail(source: ProviderRef): Album? = album(source)
        override suspend fun artistDetail(source: ProviderRef): Artist? = null
        override suspend fun radioTracks(seed: Track): List<Track> = emptyList()
        override suspend fun playlistTracks(source: ProviderRef): List<Track> = emptyList()
    }

    private class FakePlayback : PlaybackController {
        var lastPlayed: String? = null
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
        override fun playQueueItem(queueItemId: String) { lastPlayed = queueItemId }
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

    private val ref = ProviderRef("deezer", "album:302127")

    private fun vm(repo: MetadataRepository, playback: FakePlayback = FakePlayback(), queue: InMemoryQueueRepository = InMemoryQueueRepository()) =
        AlbumDetailViewModel(SavedStateHandle(mapOf("provider" to ref.provider, "id" to ref.id)), repo, queue, playback, NoDownloads())

    private fun album() = Album(
        title = "Discovery",
        tracks = listOf(Track(title = "One More Time", source = ProviderRef("deezer", "10"))),
        source = ref,
    )

    @Test
    fun `loads the album for its ref`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = vm(FakeMeta { album() })

        val content = vm.state.first { it is AlbumUiState.Content } as AlbumUiState.Content

        assertEquals("Discovery", content.album.title)
        assertEquals(listOf("One More Time"), content.album.tracks.map { it.title })
    }

    @Test
    fun `network failure yields Offline`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = vm(FakeMeta { throw AppError.Network("no route") })

        assertEquals(AlbumUiState.Offline, vm.state.first { it !is AlbumUiState.Loading })
    }

    @Test
    fun `null album yields Error`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = vm(FakeMeta { null })

        assertTrue(vm.state.first { it !is AlbumUiState.Loading } is AlbumUiState.Error)
    }

    @Test
    fun `play sets the whole album as the queue context from the tapped index`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val playback = FakePlayback()
        val vm = vm(FakeMeta { album() }, playback)
        vm.state.first { it is AlbumUiState.Content }

        vm.play(0)

        assertEquals(listOf("One More Time"), playback.lastContextTracks.map { it.title })
        assertEquals(0, playback.lastContextIndex)
    }
}
