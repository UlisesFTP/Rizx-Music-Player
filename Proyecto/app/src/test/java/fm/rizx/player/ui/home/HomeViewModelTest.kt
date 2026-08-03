package fm.rizx.player.ui.home

import fm.rizx.player.FakeSettingsRepository
import fm.rizx.player.MainDispatcherRule
import fm.rizx.player.data.local.settings.SettingsRepositoryImpl
import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.local.store.HomeFeedStore
import fm.rizx.player.data.repository.InMemoryQueueRepository
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.MixKind
import fm.rizx.player.domain.model.Playlist
import fm.rizx.player.domain.model.PlaylistSummary
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueSourceKind
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.playback.PlaybackState
import fm.rizx.player.domain.repository.DashboardRepository
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.repository.ForYouRepository
import fm.rizx.player.domain.repository.PlaylistRepository
import fm.rizx.player.domain.repository.RecentlyPlayedRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
        var lastContext: QueueContext? = null
        override fun playContext(tracks: List<Track>, startIndex: Int, context: QueueContext) { lastContext = context }
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
        override fun sections(): Flow<List<ForYouSection>> = flowOf(rows)
        override val regionalConsent: Flow<Boolean?> = consentFlow
        override suspend fun setRegionalConsent(consented: Boolean) { consentFlow.value = consented }
        override fun countryName(): String? = "México"
    }

    private class FakeRecents(private val tracks: List<Track> = emptyList()) : RecentlyPlayedRepository {
        override fun recent(limit: Int): Flow<List<Track>> = flowOf(tracks.take(limit))
        override suspend fun record(track: Track) {}
        override suspend fun clear() {}
    }

    /** The Home only reaches for this on a featured card's PLAY, which these tests don't exercise. */
    private object NoopPlaylists : PlaylistRepository {
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

    private class FakeFavorites(private val tracks: List<Track> = emptyList()) : FavoritesRepository {
        override fun favoriteTracks(): Flow<List<Track>> = flowOf(tracks)
        override fun favoriteAlbums(): Flow<List<AlbumRef>> = flowOf(emptyList())
        override fun favoriteArtists(): Flow<List<ArtistRef>> = flowOf(emptyList())
        override fun isFavoriteTrack(source: ProviderRef): Flow<Boolean> = flowOf(false)
        override suspend fun addTrack(track: Track) {}
        override suspend fun removeTrack(source: ProviderRef) {}
        override suspend fun toggleTrack(track: Track): Boolean = true
        override suspend fun addAlbum(album: AlbumRef) {}
        override suspend fun removeAlbum(source: ProviderRef) {}
        override suspend fun addArtist(artist: ArtistRef) {}
        override suspend fun removeArtist(source: ProviderRef) {}
    }

    /** Three artists — enough for a `SimilarTo` row to clear the deduper's minimum. */
    private fun artists() = List(3) { ArtistRef(name = "Artist $it", source = ProviderRef("deezer", "artist:$it")) }

    private fun feedWith(title: String) =
        HomeFeed(topTracks = listOf(AttributedResult("d", "Deezer", listOf(Track(title, source = ProviderRef("deezer", "1"))))))

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
        HomeViewModel(repo, InMemoryQueueRepository(), FakePlayback(), forYou, store(), FakeSettingsRepository(), FakeFavorites(), NoopPlaylists, FakeRecents())

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
    fun `continue listening survives a feed that never loads`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            // It is local and deliberately outside HomeUiState: an offline Home still offers a way
            // back into what you were playing, and no provider gets to take that away.
            val recent = Track("Yesterday", source = ProviderRef("deezer", "9"))
            val vm = HomeViewModel(
                FakeDash { throw AppError.Network("offline") },
                InMemoryQueueRepository(), FakePlayback(), FakeForYou(), store(), FakeSettingsRepository(), FakeFavorites(),
                NoopPlaylists, FakeRecents(listOf(recent)),
            )

            assertEquals(HomeUiState.Offline, vm.state.first { it !is HomeUiState.Loading })
            assertEquals(listOf(recent), vm.continueListening.first { it.isNotEmpty() })
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
            val forYou = FakeForYou(rows = listOf(ForYouSection.SimilarTo("Daft Punk", artists = artists)), consent = true)
            val vm = vm(FakeDash { HomeFeed() }, forYou)

            val content = vm.state.first { it is HomeUiState.Content } as HomeUiState.Content

            assertEquals(1, content.forYouSections.size)
            assertEquals(true, content.regionalConsent)
            assertEquals("México", content.countryName)
        }

    @Test
    fun `the charts render without waiting for the personalized rows, which hold their own place`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            // The For-you rows are the slow half (YouTube-backed mixes). They used to hold the whole
            // screen on a spinner; now they arrive into content that is already usable — and they
            // announce themselves first, so their height is reserved instead of shoving the charts
            // down a screen when they land.
            val gate = CompletableDeferred<Unit>()
            val artists = artists()
            val forYou = object : ForYouRepository {
                override fun sections(): Flow<List<ForYouSection>> = flow {
                    emit(listOf(ForYouSection.SimilarTo("Daft Punk"))) // the plan
                    gate.await()
                    emit(listOf(ForYouSection.SimilarTo("Daft Punk", artists = artists)))
                }
                override val regionalConsent: Flow<Boolean?> = MutableStateFlow(true)
                override suspend fun setRegionalConsent(consented: Boolean) {}
                override fun countryName(): String? = "México"
            }
            val vm = vm(FakeDash { feedWith("Yellow") }, forYou)

            val early = vm.state.first { it is HomeUiState.Content } as HomeUiState.Content
            assertEquals(1, early.feed.topTracks.size)
            assertTrue(early.forYouSections.isEmpty())
            assertEquals("the row's space must already be reserved", 1, early.forYouPending.size)

            gate.complete(Unit)
            val complete = vm.state.first { it is HomeUiState.Content && it.forYouSections.isNotEmpty() }

            assertEquals(1, (complete as HomeUiState.Content).forYouSections.size)
            assertTrue("the skeleton must go when the row it stood in for lands", complete.forYouPending.isEmpty())
            // Nothing the user was already looking at was taken away.
            assertEquals(1, complete.feed.topTracks.size)
        }

    @Test
    fun `a refresh never draws skeletons under rows that are already on screen`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            // The plan is emitted on every load, refreshes included. Drawing it while the real rows are
            // still up would append an empty copy of them — the very jump the reservation prevents.
            val second = CompletableDeferred<Unit>()
            val forYou = object : ForYouRepository {
                var loads = 0
                override fun sections(): Flow<List<ForYouSection>> = flow {
                    val first = loads++ == 0
                    emit(listOf(ForYouSection.SimilarTo("Daft Punk")))
                    if (!first) second.await() // the refresh parks after announcing
                    emit(listOf(ForYouSection.SimilarTo("Daft Punk", artists = artists())))
                }
                override val regionalConsent: Flow<Boolean?> = MutableStateFlow(true)
                override suspend fun setRegionalConsent(consented: Boolean) {}
                override fun countryName(): String? = "México"
            }
            val vm = vm(FakeDash { feedWith("Yellow") }, forYou)
            vm.state.first { it is HomeUiState.Content && it.forYouSections.isNotEmpty() }

            vm.refresh()
            mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

            val during = vm.state.value as HomeUiState.Content
            assertEquals("the rows stay put", 1, during.forYouSections.size)
            assertTrue("no second, empty copy of them below", during.forYouPending.isEmpty())
            second.complete(Unit)
        }

    @Test
    fun `a cached Home is served without going to the network`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val cache = store()
            val cached = HomeFeed(topTracks = listOf(AttributedResult("d", "Deezer", listOf(Track("From cache", source = ProviderRef("deezer", "1"))))))
            // Written under the selection the ViewModel will read with — the cache is keyed by it.
            cache.write(cached, emptyList(), SettingsRepositoryImpl.DEFAULT_FEED_PROVIDER)
            var networkCalls = 0
            val dash = FakeDash { networkCalls++; HomeFeed() }

            val vm = HomeViewModel(dash, InMemoryQueueRepository(), FakePlayback(), FakeForYou(), cache, FakeSettingsRepository(), FakeFavorites(), NoopPlaylists, FakeRecents())
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
                SettingsRepositoryImpl.DEFAULT_FEED_PROVIDER,
            )
            val vm = HomeViewModel(
                FakeDash { throw AppError.Network("offline") },
                InMemoryQueueRepository(), FakePlayback(), FakeForYou(), cache, FakeSettingsRepository(), FakeFavorites(),
                NoopPlaylists, FakeRecents(),
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
            val vm = HomeViewModel(FakeDash { HomeFeed() }, InMemoryQueueRepository(), playback, FakeForYou(), store(), FakeSettingsRepository(), FakeFavorites(), NoopPlaylists, FakeRecents())
            val youtube = Track("Mix song", source = ProviderRef("youtube", "abcdefghijk"))
            val deezer = Track("Chart song", source = ProviderRef("deezer", "1"))

            vm.playTrack(youtube)
            vm.playTrack(deezer)

            assertEquals(listOf(youtube, deezer), playback.autoRadioTracks)
            assertNull("the screen must not pick an engine itself", playback.lastYoutubeRadioTrack)
        }

    @Test
    fun `the mosaic wall is built from the feed and plays as a context, not a radio`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val playback = FakePlayback()
            val vm = HomeViewModel(
                FakeDash { chartFeed(10) }, InMemoryQueueRepository(), playback, FakeForYou(), store(),
                FakeSettingsRepository(), FakeFavorites(), NoopPlaylists, FakeRecents(),
            )

            val mix = vm.mixes.first { it.mixes.isNotEmpty() }.mixes.first()
            vm.playMix(mix, "Around the world")

            // A mix the app assembled is a finished list: next/previous must walk it rather than wander
            // off into a radio after the first song.
            assertEquals(MixKind.GLOBAL, mix.kind)
            assertEquals(10, mix.tracks.size)
            assertEquals(QueueSourceKind.PLAYLIST, playback.lastContext?.kind)
            assertEquals("Around the world", playback.lastContext?.label)
        }

    @Test
    fun `the wall does not change when the slow personalized half lands — only the pick does`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            // The whole reason MixBuilder is not fed the For-you rows: a wall that gained a tile when
            // they arrived would push the entire feed down, which is the jump the skeletons exist to
            // prevent. The day's pick *does* come from them, and the Home reserves its card's height.
            val gate = CompletableDeferred<Unit>()
            // Three, because the Home's deduper drops any row thinner than that — a one-track row would
            // never have reached the UI to be picked from.
            val recommended = List(3) { Track("Recommended $it", source = ProviderRef("youtube", "yt$it")) }
            val forYou = object : ForYouRepository {
                override fun sections(): Flow<List<ForYouSection>> = flow {
                    emit(listOf(ForYouSection.Mix("Timeless", emptyList())))
                    gate.await()
                    emit(listOf(ForYouSection.Mix("Timeless", recommended)))
                }
                override val regionalConsent: Flow<Boolean?> = MutableStateFlow(true)
                override suspend fun setRegionalConsent(consented: Boolean) {}
                override fun countryName(): String? = "México"
            }
            val vm = vm(FakeDash { chartFeed(10) }, forYou)

            val before = vm.mixes.first { it.mixes.isNotEmpty() }
            assertNull("nothing has been recommended yet", before.pick)

            gate.complete(Unit)
            val after = vm.mixes.first { it.pick != null }

            assertEquals("the wall must be exactly what it was", before.mixes, after.mixes)
            assertEquals(recommended.first(), after.pick?.track)
            assertEquals("the reason line names the song that earned it", "Timeless", after.pick?.becauseOf)
        }

    /** A chart big enough to clear the global mix's minimum size. */
    private fun chartFeed(count: Int) = HomeFeed(
        topTracks = listOf(
            AttributedResult(
                "d", "Deezer",
                List(count) { Track("Chart $it", source = ProviderRef("deezer", "t$it")) },
            ),
        ),
    )
}
