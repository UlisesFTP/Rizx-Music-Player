package fm.rizx.player.ui.detail

import androidx.lifecycle.SavedStateHandle
import fm.rizx.player.MainDispatcherRule
import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.artist.ArtistBioSource
import fm.rizx.player.data.repository.InMemoryQueueRepository
import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.ArtistBio
import fm.rizx.player.domain.model.ArtistRef
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeMeta(
        val artist: (ProviderRef) -> Artist?,
        val similar: () -> List<ArtistRef> = { emptyList() },
    ) : MetadataRepository {
        override suspend fun search(params: SearchParams) = SearchResults()
        override suspend fun albumDetail(source: ProviderRef): Album? = null
        override suspend fun artistDetail(source: ProviderRef): Artist? = artist(source)
        override suspend fun relatedArtists(source: ProviderRef): List<ArtistRef> = similar()
        override suspend fun radioTracks(seed: Track): List<Track> = emptyList()
        override suspend fun playlistTracks(source: ProviderRef): List<Track> = emptyList()
    }

    private class FakeBios(val bio: () -> ArtistBio?) : ArtistBioSource {
        override suspend fun bioFor(key: String, artistName: String): ArtistBio? = bio()
    }

    private class FakePlayback : PlaybackController {
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
        override fun playQueueItem(queueItemId: String) {}
        override fun playTrack(track: Track) {}
        var lastContextTracks: List<Track> = emptyList()
        var lastContextIndex: Int = -1
        var lastRadio: Track? = null
        override fun playContext(tracks: List<Track>, startIndex: Int, context: fm.rizx.player.domain.model.QueueContext) {
            lastContextTracks = tracks; lastContextIndex = startIndex
        }
        override fun playRadio(track: Track) { lastRadio = track }
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

    private fun vm(
        repo: MetadataRepository,
        playback: PlaybackController = FakePlayback(),
        queue: InMemoryQueueRepository = InMemoryQueueRepository(),
        bios: ArtistBioSource = FakeBios { null },
    ) = ArtistDetailViewModel(
        SavedStateHandle(mapOf("provider" to ref.provider, "id" to ref.id)),
        repo,
        queue,
        playback,
        bios,
    )

    private fun track(title: String) = Track(title = title, source = ProviderRef("deezer", title))

    @Test
    fun `loads the artist with top tracks`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val artist = Artist(
            name = "Daft Punk",
            topTracks = listOf(track("Aerodynamic")),
            followers = 9_600_000,
            source = ref,
        )
        val vm = vm(FakeMeta(artist = { artist }))

        val content = vm.state.first { it is ArtistUiState.Content } as ArtistUiState.Content

        assertEquals("Daft Punk", content.artist.name)
        assertEquals(listOf("Aerodynamic"), content.artist.topTracks.map { it.title })
    }

    @Test
    fun `network failure yields Offline`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = vm(FakeMeta(artist = { throw AppError.Network("offline") }))

        assertEquals(ArtistUiState.Offline, vm.state.first { it !is ArtistUiState.Loading })
    }

    @Test
    fun `similar artists and the biography arrive with the page`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = vm(
            FakeMeta(
                artist = { Artist(name = "Daft Punk", source = ref) },
                similar = { listOf(ArtistRef(name = "Justice", source = ProviderRef("deezer", "artist:1"))) },
            ),
            bios = FakeBios { ArtistBio("A French duo…", "https://en.wikipedia.org/wiki/Daft_Punk") },
        )

        val content = vm.state.first { it is ArtistUiState.Content } as ArtistUiState.Content

        assertEquals(listOf("Justice"), content.similar.map { it.name })
        assertEquals("A French duo…", content.bio?.text)
    }

    @Test
    fun `an optional section that fails takes nothing else down`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = vm(
            FakeMeta(
                artist = { Artist(name = "Daft Punk", source = ref) },
                similar = { error("deezer is down") },
            ),
            bios = FakeBios { error("wikipedia is down") },
        )

        val content = vm.state.first { it is ArtistUiState.Content } as ArtistUiState.Content

        assertEquals("Daft Punk", content.artist.name)
        assertTrue(content.similar.isEmpty())
        assertNull(content.bio)
    }

    @Test
    fun `playing a song plays the list it was tapped in, at that position`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val all = listOf(track("A"), track("B"), track("C"))
            val playback = FakePlayback()
            val vm = vm(FakeMeta(artist = { Artist(name = "X", topTracks = all, source = ref) }), playback)
            vm.state.first { it is ArtistUiState.Content }

            // What a filtered list hands over: only the matching songs, and an index into *them*.
            val visible = listOf(all[2])
            vm.play(0, visible)

            assertEquals(visible, playback.lastContextTracks)
            assertEquals(0, playback.lastContextIndex)
        }

    @Test
    fun `shuffle records the preference before the queue is filled`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val queue = InMemoryQueueRepository()
            val playback = FakePlayback()
            val all = listOf(track("A"), track("B"))
            val vm = vm(FakeMeta(artist = { Artist(name = "X", topTracks = all, source = ref) }), playback, queue)
            vm.state.first { it is ArtistUiState.Content }

            vm.shuffle(all)

            // The other order would leave the first track pinned, which is not what shuffle means.
            assertTrue(queue.state.value.shuffleOn)
            assertEquals(all, playback.lastContextTracks)
        }

    @Test
    fun `radio follows the engine chosen in settings`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val playback = FakePlayback()
        val all = listOf(track("A"), track("B"))
        val vm = vm(FakeMeta(artist = { Artist(name = "X", topTracks = all, source = ref) }), playback)
        vm.state.first { it is ArtistUiState.Content }

        vm.radio(all)

        // playAutoRadio defaults to playRadio in the controller, so this is the seam that got called.
        assertEquals("A", playback.lastRadio?.title)
    }
}
