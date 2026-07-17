package fm.rizx.player.ui.home

import fm.rizx.player.MainDispatcherRule
import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.repository.InMemoryQueueRepository
import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.playback.PlaybackState
import fm.rizx.player.domain.repository.DashboardRepository
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
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeDash(val feed: suspend () -> HomeFeed) : DashboardRepository {
        override suspend fun homeFeed(): HomeFeed = feed()
    }

    private class FakePlayback : PlaybackController {
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
        override fun playQueueItem(queueItemId: String) {}
        override fun playTrack(track: Track) {}
        var lastRadioTrack: Track? = null
        override fun playContext(tracks: List<Track>, startIndex: Int, context: fm.rizx.player.domain.model.QueueContext) {}
        override fun playRadio(track: Track) { lastRadioTrack = track }
        override fun play() {}
        override fun pause() {}
        override fun toggle() {}
        override fun stop() {}
        override fun seekTo(positionMs: Long) {}
        override fun skipNext() {}
        override fun skipPrevious() {}
        override fun release() {}
    }

    private fun vm(repo: DashboardRepository) = HomeViewModel(repo, InMemoryQueueRepository(), FakePlayback())

    @Test
    fun `loads the feed into Content`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val feed = HomeFeed(topTracks = listOf(AttributedResult("d", "Deezer", listOf(Track("Yellow", source = ProviderRef("deezer", "1"))))))
        val vm = vm(FakeDash { feed })

        val content = vm.state.first { it is HomeUiState.Content } as HomeUiState.Content

        assertEquals(1, content.feed.topTracks.size)
    }

    @Test
    fun `network failure yields Offline`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = vm(FakeDash { throw AppError.Network("offline") })

        assertEquals(HomeUiState.Offline, vm.state.first { it !is HomeUiState.Loading })
    }

    @Test
    fun `an empty feed yields Error`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = vm(FakeDash { HomeFeed() })

        assertTrue(vm.state.first { it !is HomeUiState.Loading } is HomeUiState.Error)
    }
}
