package fm.rizx.player.ui.home

import fm.rizx.player.MainDispatcherRule
import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.local.store.HomeFeedStore
import fm.rizx.player.data.repository.InMemoryQueueRepository
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.playback.PlaybackState
import fm.rizx.player.domain.repository.DashboardRepository
import fm.rizx.player.domain.repository.ForYouRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
        var lastYoutubeRadioTrack: Track? = null
        val autoRadioTracks = mutableListOf<Track>()
        override fun playAutoRadio(track: Track) { autoRadioTracks += track }
        override fun playContext(tracks: List<Track>, startIndex: Int, context: fm.rizx.player.domain.model.QueueContext) {}
        override fun playRadio(track: Track) { lastRadioTrack = track }
        override fun playYoutubeRadio(track: Track) { lastYoutubeRadioTrack = track }
        override fun play() {}
        override fun pause() {}
        override fun toggle() {}
        override fun stop() {}
        override fun seekTo(positionMs: Long) {}
        override fun skipNext() {}
        override fun skipPrevious() {}
        override fun release() {}
    }

    private class FakeForYou(
        private val rows: List<ForYouSection> = emptyList(),
        consent: Boolean? = null,
    ) : ForYouRepository {
        val consentFlow = MutableStateFlow(consent)
        override suspend fun sections(): List<ForYouSection> = rows
        override val regionalConsent: Flow<Boolean?> = consentFlow
        override suspend fun setRegionalConsent(consented: Boolean) { consentFlow.value = consented }
        override fun countryName(): String? = "México"
    }

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * A store over a file that doesn't exist yet: every test starts with an empty Home cache. Its IO
     * runs on the test scheduler so `runTest` drains the write-back instead of leaving it in flight on
     * a real thread after `Dispatchers.Main` has been torn down.
     */
    private fun store() =
        HomeFeedStore(File(tmp.root, "home_feed.json"), io = mainDispatcherRule.dispatcher)

    private fun vm(repo: DashboardRepository, forYou: ForYouRepository = FakeForYou()) =
        HomeViewModel(repo, InMemoryQueueRepository(), FakePlayback(), forYou, store())

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

    @Test
    fun `for-you sections alone keep Content alive and carry consent + country`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val artists = List(3) { ArtistRef(name = "Artist $it", source = ProviderRef("deezer", "artist:$it")) }
            val forYou = FakeForYou(rows = listOf(ForYouSection.ArtistsForYou(artists)), consent = true)
            val vm = vm(FakeDash { HomeFeed() }, forYou)

            val content = vm.state.first { it is HomeUiState.Content } as HomeUiState.Content

            assertEquals(1, content.forYouSections.size)
            assertEquals(true, content.regionalConsent)
            assertEquals("México", content.countryName)
        }

    @Test
    fun `the charts render without waiting for the personalized rows`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            // The For-you rows are the slow half (YouTube-backed mixes). They used to hold the whole
            // screen on a spinner; now they arrive into content that is already usable.
            val feed = HomeFeed(topTracks = listOf(AttributedResult("d", "Deezer", listOf(Track("Yellow", source = ProviderRef("deezer", "1"))))))
            val gate = CompletableDeferred<Unit>()
            val artists = List(3) { ArtistRef(name = "Artist $it", source = ProviderRef("deezer", "artist:$it")) }
            val forYou = object : ForYouRepository {
                override suspend fun sections(): List<ForYouSection> {
                    gate.await()
                    return listOf(ForYouSection.ArtistsForYou(artists))
                }
                override val regionalConsent: Flow<Boolean?> = MutableStateFlow(true)
                override suspend fun setRegionalConsent(consented: Boolean) {}
                override fun countryName(): String? = "México"
            }
            val vm = vm(FakeDash { feed }, forYou)

            val early = vm.state.first { it is HomeUiState.Content } as HomeUiState.Content
            assertEquals(1, early.feed.topTracks.size)
            assertTrue("the rows should still be marked as loading", early.forYouLoading)
            assertTrue(early.forYouSections.isEmpty())

            gate.complete(Unit)
            val complete = vm.state.first { it is HomeUiState.Content && it.forYouSections.isNotEmpty() }

            assertEquals(1, (complete as HomeUiState.Content).forYouSections.size)
            // Nothing the user was already looking at was taken away.
            assertEquals(1, complete.feed.topTracks.size)
        }

    @Test
    fun `a cached Home is served without going to the network`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val cache = store()
            val cached = HomeFeed(topTracks = listOf(AttributedResult("d", "Deezer", listOf(Track("From cache", source = ProviderRef("deezer", "1"))))))
            cache.write(cached, emptyList())
            var networkCalls = 0
            val dash = FakeDash { networkCalls++; HomeFeed() }

            val vm = HomeViewModel(dash, InMemoryQueueRepository(), FakePlayback(), FakeForYou(), cache)
            val content = vm.state.first { it is HomeUiState.Content } as HomeUiState.Content

            assertEquals("From cache", content.feed.topTracks.single().items.single().title)
            assertEquals("a fresh cache should not trigger a refetch", 0, networkCalls)
        }

    @Test
    fun `a failing refresh leaves the cached Home on screen instead of an error`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val cache = store()
            cache.write(
                HomeFeed(topTracks = listOf(AttributedResult("d", "Deezer", listOf(Track("From cache", source = ProviderRef("deezer", "1")))))),
                emptyList(),
            )
            val vm = HomeViewModel(
                FakeDash { throw AppError.Network("offline") },
                InMemoryQueueRepository(), FakePlayback(), FakeForYou(), cache,
            )

            vm.state.first { it is HomeUiState.Content }
            vm.refresh()

            assertTrue(vm.state.value is HomeUiState.Content)
        }

    @Test
    fun `a feed play defers to the chosen recommendation engine, whatever the song's source`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            // Home used to branch on the track's provider. Which engine fills "next" is now the user's
            // setting, resolved in one place (the controller), so every entry point agrees — this
            // screen just says "start a radio from this song".
            val playback = FakePlayback()
            val vm = HomeViewModel(FakeDash { HomeFeed() }, InMemoryQueueRepository(), playback, FakeForYou(), store())
            val youtube = Track("Mix song", source = ProviderRef("youtube", "abcdefghijk"))
            val deezer = Track("Chart song", source = ProviderRef("deezer", "1"))

            vm.playTrack(youtube)
            vm.playTrack(deezer)

            assertEquals(listOf(youtube, deezer), playback.autoRadioTracks)
            assertNull("the screen must not pick an engine itself", playback.lastYoutubeRadioTrack)
        }
}
