package fm.rizx.player.ui.detail

import androidx.lifecycle.SavedStateHandle
import fm.rizx.player.MainDispatcherRule
import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.repository.InMemoryQueueRepository
import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.playback.PlaybackState
import fm.rizx.player.domain.repository.MetadataRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeMeta(val artist: (ProviderRef) -> Artist?) : MetadataRepository {
        override suspend fun search(params: SearchParams) = SearchResults()
        override suspend fun albumDetail(source: ProviderRef): Album? = null
        override suspend fun artistDetail(source: ProviderRef): Artist? = artist(source)
        override suspend fun radioTracks(seed: Track): List<Track> = emptyList()
        override suspend fun playlistTracks(source: ProviderRef): List<Track> = emptyList()
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

    private val ref = ProviderRef("deezer", "artist:27")

    private fun vm(repo: MetadataRepository) =
        ArtistDetailViewModel(SavedStateHandle(mapOf("provider" to ref.provider, "id" to ref.id)), repo, InMemoryQueueRepository(), FakePlayback())

    @Test
    fun `loads the artist with top tracks`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val artist = Artist(
            name = "Daft Punk",
            topTracks = listOf(Track(title = "Aerodynamic", source = ProviderRef("deezer", "11"))),
            followers = 9_600_000,
            source = ref,
        )
        val vm = vm(FakeMeta { artist })

        val content = vm.state.first { it is ArtistUiState.Content } as ArtistUiState.Content

        assertEquals("Daft Punk", content.artist.name)
        assertEquals(listOf("Aerodynamic"), content.artist.topTracks.map { it.title })
    }

    @Test
    fun `network failure yields Offline`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = vm(FakeMeta { throw AppError.Network("offline") })

        assertEquals(ArtistUiState.Offline, vm.state.first { it !is ArtistUiState.Loading })
    }
}
