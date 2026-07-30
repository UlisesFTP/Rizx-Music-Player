package fm.rizx.player.ui.search

import fm.rizx.player.MainDispatcherRule
import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.local.store.SearchHistoryStore
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchCategory
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.repository.MetadataRepository
import fm.rizx.player.domain.repository.RecentlyPlayedRepository
import fm.rizx.player.domain.usecase.SearchMusicUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val noopFavorites = object : FavoritesRepository {
        override fun favoriteTracks() = flowOf(emptyList<Track>())
        override fun favoriteAlbums() = flowOf(emptyList<AlbumRef>())
        override fun favoriteArtists() = flowOf(emptyList<ArtistRef>())
        override fun isFavoriteTrack(source: ProviderRef) = flowOf(false)
        override suspend fun addTrack(track: Track) {}
        override suspend fun removeTrack(source: ProviderRef) {}
        override suspend fun toggleTrack(track: Track): Boolean = true
        override suspend fun addAlbum(album: AlbumRef) {}
        override suspend fun removeAlbum(source: ProviderRef) {}
        override suspend fun addArtist(artist: ArtistRef) {}
        override suspend fun removeArtist(source: ProviderRef) {}
    }

    private val emptyStreaming = object : fm.rizx.player.data.search.StreamingSourcesSearch {
        override suspend fun search(query: String) = SearchResults()
    }

    private val emptyPlaylists = object : fm.rizx.player.data.search.PlaylistSourcesSearch {
        override suspend fun search(query: String) = SearchResults()
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeRecents(private val tracks: List<Track> = emptyList()) : RecentlyPlayedRepository {
        override fun recent(limit: Int): Flow<List<Track>> = flowOf(tracks.take(limit))
        override suspend fun record(track: Track) {}
        override suspend fun clear() {}
    }

    /** A history over a file that does not exist yet: every test starts with nothing searched. */
    private fun store() =
        SearchHistoryStore(File(tmp.root, "search_history.json"), io = mainDispatcherRule.dispatcher)

    private fun vmWith(
        repo: MetadataRepository,
        streaming: fm.rizx.player.data.search.StreamingSourcesSearch = emptyStreaming,
        playlists: fm.rizx.player.data.search.PlaylistSourcesSearch = emptyPlaylists,
        suggest: List<String> = emptyList(),
        history: SearchHistoryStore = store(),
        played: List<Track> = emptyList(),
    ) = SearchViewModel(
        SearchMusicUseCase(repo),
        streaming,
        playlists,
        noopFavorites,
        youtube = suggestingClient(suggest),
        history = history,
        recents = FakeRecents(played),
    )
        // On the test scheduler, so `runTest` drains the suggestion lookup instead of leaving it in
        // flight on a real IO thread after `Dispatchers.Main` has been torn down.
        .useIoDispatcher(mainDispatcherRule.dispatcher)

    /** Only `suggestions` matters here; everything else on the client is unused by the ViewModel. */
    private fun suggestingClient(results: List<String>) =
        object : fm.rizx.player.data.remote.youtube.YoutubeExtractorClient {
            override fun searchSongs(query: String, limit: Int) = emptyList<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
            override fun searchVideos(query: String, limit: Int) = emptyList<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
            override fun searchPlaylists(query: String, limit: Int) = emptyList<org.schabi.newpipe.extractor.playlist.PlaylistInfoItem>()
            override fun streamInfo(videoUrl: String): org.schabi.newpipe.extractor.stream.StreamInfo = error("not used")
            override fun playlist(playlistUrl: String) = error("not used")
            override fun mix(videoId: String, limit: Int) = emptyList<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
            override fun suggestions(query: String, limit: Int) = results.take(limit)
        }

    private fun fakeRepo(block: suspend (SearchParams) -> SearchResults) = object : MetadataRepository {
        override suspend fun search(params: SearchParams): SearchResults = block(params)
        override suspend fun albumDetail(source: fm.rizx.player.domain.model.ProviderRef) = null
        override suspend fun artistDetail(source: fm.rizx.player.domain.model.ProviderRef) = null
        override suspend fun radioTracks(seed: Track): List<Track> = emptyList()
        override suspend fun playlistTracks(source: fm.rizx.player.domain.model.ProviderRef): List<Track> = emptyList()
    }

    private fun tracks(vararg titles: String) =
        SearchResults(tracks = titles.map { Track(title = it, source = ProviderRef("f", it)) })

    @Test
    fun `blank query stays idle`() {
        val vm = vmWith(fakeRepo { SearchResults() })
        vm.onQueryChange("   ")
        assertEquals(SearchUiState.Idle, vm.uiState.value)
    }

    @Test
    fun `query yields results after debounce`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = vmWith(fakeRepo { tracks("Velvet Hours") })
        vm.onQueryChange("velvet")
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state is SearchUiState.Results)
        assertEquals("Velvet Hours", (state as SearchUiState.Results).results.tracks.single().title)
    }

    @Test
    fun `no matches yields empty`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = vmWith(fakeRepo { SearchResults() })
        vm.onQueryChange("zzz")
        advanceUntilIdle()
        assertEquals(SearchUiState.Empty, vm.uiState.value)
    }

    @Test
    fun `provider failure yields a safe error message, never the raw exception text`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = vmWith(fakeRepo { throw RuntimeException("boom") })
        vm.onQueryChange("x")
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state is SearchUiState.Error)
        assertEquals("Something went wrong", (state as SearchUiState.Error).message)
    }

    @Test
    fun `network failure yields offline, not a generic error`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = vmWith(fakeRepo { throw AppError.Network("no route to host") })
        vm.onQueryChange("x")
        advanceUntilIdle()
        assertEquals(SearchUiState.Offline, vm.uiState.value)
    }

    @Test
    fun `retry re-runs the query and recovers`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        var online = false
        val vm = vmWith(fakeRepo { if (online) tracks("Yellow") else throw AppError.Network("offline") })
        vm.onQueryChange("yellow")
        advanceUntilIdle()
        assertEquals(SearchUiState.Offline, vm.uiState.value)

        online = true
        vm.retry()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state is SearchUiState.Results)
        assertEquals("Yellow", (state as SearchUiState.Results).results.tracks.single().title)
    }

    @Test
    fun `rapid typing debounces to the last query only`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val seen = mutableListOf<String>()
        val vm = vmWith(fakeRepo { seen += it.query; SearchResults() })
        vm.onQueryChange("a")
        advanceTimeBy(100) // less than the 300ms debounce
        vm.onQueryChange("ab")
        advanceUntilIdle()
        assertEquals(listOf("ab"), seen)
    }

    @Test
    fun `the Artists tab requests artists-only from the catalog`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        var capturedTypes: List<SearchCategory>? = null
        val repo = fakeRepo { params ->
            capturedTypes = params.types
            SearchResults(artists = listOf(ArtistRef(name = "Daft Punk", source = ProviderRef("deezer", "artist:1"))))
        }
        val vm = vmWith(repo)
        vm.onQueryChange("daft")
        advanceUntilIdle()

        vm.selectTab(SearchTab.Artists)
        advanceUntilIdle()

        assertEquals(listOf(SearchCategory.ARTISTS), capturedTypes) // narrowed to the dedicated artist index
        val state = vm.uiState.value as SearchUiState.Results
        assertEquals("Daft Punk", state.results.artists.single().name)
    }

    @Test
    fun `the Playlists tab searches the playlist sources, not the catalog`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val playlists = object : fm.rizx.player.data.search.PlaylistSourcesSearch {
            override suspend fun search(query: String) =
                SearchResults(playlists = listOf(PlaylistRef(id = "1", name = "Chill Vibes", source = ProviderRef("deezer", "playlist:1"))))
        }
        val vm = vmWith(fakeRepo { SearchResults() }, playlists = playlists)
        vm.onQueryChange("chill")
        advanceUntilIdle()

        vm.selectTab(SearchTab.Playlists)
        advanceUntilIdle()

        val state = vm.uiState.value as SearchUiState.Results
        assertEquals("Chill Vibes", state.results.playlists.single().name)
        assertEquals(SearchTab.Playlists, vm.tab.value)
    }

    @Test
    fun `the Underground tab searches YouTube plus SoundCloud, not the catalog`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        var metadataHit = false
        val streaming = object : fm.rizx.player.data.search.StreamingSourcesSearch {
            override suspend fun search(query: String) =
                SearchResults(tracks = listOf(Track(title = "Exclusive Remix", source = ProviderRef("youtube", "abc"))))
        }
        val vm = vmWith(fakeRepo { metadataHit = true; SearchResults() }, streaming)

        // Default Songs tab hits only the catalog (empty here) — never the streaming sources.
        vm.onQueryChange("remix")
        advanceUntilIdle()
        assertTrue(metadataHit)
        assertEquals(SearchUiState.Empty, vm.uiState.value)

        // Switching to Underground re-runs the same query against YouTube + SoundCloud instead.
        vm.selectTab(SearchTab.Underground)
        advanceUntilIdle()

        val state = vm.uiState.value as SearchUiState.Results
        assertEquals("Exclusive Remix", state.results.tracks.single().title)
        assertEquals(SearchTab.Underground, vm.tab.value)
    }

    // ---- History, pills and the suggestion list -----------------------------------------------------

    @Test
    fun `only a deliberate search is remembered, not every keystroke`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            // Recording the debounced query would bury "the weeknd" under "t", "th", "the"...
            val history = store()
            val vm = vmWith(fakeRepo { SearchResults() }, history = history)

            vm.onQueryChange("the wee")
            advanceUntilIdle()
            assertEquals(emptyList<String>(), history.queries().first())

            vm.searchFor("The Weeknd")
            advanceUntilIdle()
            assertEquals(listOf("The Weeknd"), history.queries().first())
        }

    @Test
    fun `the pills lead with what was searched, then what is played, then the fallback`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val played = List(3) {
                Track("Song $it", artists = listOf(ArtistCredit("Feid")), source = ProviderRef("d", "$it"))
            }
            val history = store()
            history.remember("Rosalía")
            val vm = vmWith(fakeRepo { SearchResults() }, history = history, played = played)

            val pills = vm.pills.first { it.isNotEmpty() }

            assertEquals("Rosalía", pills[0].text)
            assertTrue("a searched pill is marked as history", pills[0].recent)
            assertEquals("Feid", pills[1].text)
            assertTrue("an artist you play is not a past search", !pills[1].recent)
            // The curated names fill what is left rather than being dropped.
            assertTrue(pills.any { it.text == "Daft Punk" })
            assertEquals(8, pills.size)
        }

    @Test
    fun `a name searched and played is one pill, whatever its spelling`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val played = listOf(
                Track("Levitating", artists = listOf(ArtistCredit("DualipaVEVO")), source = ProviderRef("yt", "1")),
            )
            val history = store()
            history.remember("dua lipa")
            val vm = vmWith(fakeRepo { SearchResults() }, history = history, played = played)

            val pills = vm.pills.first { it.isNotEmpty() }

            assertEquals(1, pills.count { it.text.lowercase().replace(" ", "").contains("dualipa") })
        }

    @Test
    fun `past searches head the suggestion list without making it any taller`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val history = store()
            listOf("weeknd blinding lights", "weeknd starboy", "coldplay").forEach { history.remember(it) }
            val vm = vmWith(
                fakeRepo { SearchResults() },
                suggest = listOf("weeknd tour", "weeknd 2026", "weeknd songs", "weeknd album", "weeknd merch"),
                history = history,
            )

            vm.onQueryChange("weeknd")
            advanceUntilIdle()
            val rows = vm.suggestions.first { it.isNotEmpty() }

            assertEquals("the list is exactly as tall as it was before recents existed", 5, rows.size)
            assertEquals(listOf(true, true, false, false, false), rows.map { it.recent })
            // Newest first, and only the two most recent take a slot.
            assertEquals("weeknd starboy", rows[0].text)
            assertEquals("weeknd blinding lights", rows[1].text)
        }

    @Test
    fun `a past search matches at one character, before the network says anything`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val history = store()
            history.remember("Tame Impala")
            val vm = vmWith(fakeRepo { SearchResults() }, suggest = listOf("never asked"), history = history)

            vm.onQueryChange("t")
            advanceUntilIdle()
            val rows = vm.suggestions.first { it.isNotEmpty() }

            assertEquals(listOf("Tame Impala"), rows.map { it.text })
        }

    @Test
    fun `a past search identical to the query is not offered back`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            // Offering "coldplay" while "coldplay" is in the field is a row that does nothing.
            val history = store()
            history.remember("Coldplay")
            val vm = vmWith(fakeRepo { SearchResults() }, suggest = listOf("coldplay live"), history = history)

            vm.onQueryChange("coldplay")
            advanceUntilIdle()
            val rows = vm.suggestions.first { it.isNotEmpty() }

            assertEquals(listOf("coldplay live"), rows.map { it.text })
            assertTrue(rows.none { it.recent })
        }

    @Test
    fun `picking a suggestion remembers it and closes the list`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val history = store()
            val vm = vmWith(fakeRepo { tracks("Yellow") }, suggest = listOf("coldplay yellow"), history = history)
            vm.onQueryChange("cold")
            advanceUntilIdle()
            assertEquals(listOf("coldplay yellow"), vm.suggestions.first { it.isNotEmpty() }.map { it.text })

            vm.applySuggestion("coldplay yellow")
            advanceUntilIdle()

            // `suggestions` is shared WhileSubscribed, so it has to be collected to be read at all.
            assertTrue("the user has chosen - stop suggesting", vm.suggestions.first().isEmpty())
            assertEquals(listOf("coldplay yellow"), history.queries().first())
            assertEquals("coldplay yellow", vm.query.value)
        }
}
