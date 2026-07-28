package fm.rizx.player.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.error.AppError
import fm.rizx.player.core.error.toSafeMessage
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.search.PlaylistSourcesSearch
import fm.rizx.player.data.search.StreamingSourcesSearch
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchCategory
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.usecase.SearchMusicUseCase
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** UI state for the Search screen. */
sealed interface SearchUiState {
    /** No (non-blank) query yet — the screen shows recents + browse. */
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data object Empty : SearchUiState
    data class Results(val results: SearchResults) : SearchUiState
    /** No connectivity (a real provider raised [AppError.Network]) — offer Retry, don't crash. */
    data object Offline : SearchUiState
    data class Error(val message: String) : SearchUiState
}

/**
 * The search source tabs. [Songs]/[Artists]/[Albums] hit the normal catalog (the active metadata provider
 * — Deezer); [Playlists] pulls playlists from Deezer + YouTube; [Underground] pulls songs straight from
 * YouTube + SoundCloud for remixes, edits and indie/emerging artists.
 */
enum class SearchTab(val label: String) {
    Songs("Songs"), Artists("Artists"), Albums("Albums"), Playlists("Playlists"), Underground("Underground")
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchMusic: SearchMusicUseCase,
    private val streamingSources: StreamingSourcesSearch,
    private val playlistSources: PlaylistSourcesSearch,
    private val favorites: FavoritesRepository,
    private val youtube: YoutubeExtractorClient,
) : ViewModel() {

    /**
     * Where NewPipe's blocking suggestion call runs. A plain property rather than a constructor
     * parameter because Hilt injects every parameter of an `@HiltViewModel` and there is no
     * `CoroutineDispatcher` binding — see [useIoDispatcher] for why it is overridable at all.
     */
    private var io: CoroutineDispatcher = Dispatchers.IO

    /**
     * Test seam. Left on the real IO pool, a suggestion lookup outlives the test that started it, and
     * resuming onto a `Dispatchers.Main` that has already been torn down kills the coroutine machinery
     * — which then surfaces as a failure in whichever *other* test class happens to run next.
     */
    @VisibleForTesting
    internal fun useIoDispatcher(dispatcher: CoroutineDispatcher) = apply { io = dispatcher }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _tab = MutableStateFlow(SearchTab.Songs)
    val tab: StateFlow<SearchTab> = _tab.asStateFlow()

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** Sources of favorited tracks, so each search row shows a live filled/outline heart. */
    val favoriteSources: StateFlow<Set<ProviderRef>> =
        favorites.favoriteTracks()
            .map { tracks -> tracks.map { it.source }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Likes or unlikes [track] (the heart on a search result). */
    fun toggleFavorite(track: Track) {
        viewModelScope.launch { favorites.toggleTrack(track) }
    }

    /**
     * Autocomplete for what's being typed. Kept apart from [uiState] on purpose: suggestions are a hint
     * about the *query*, not a result, and they must never replace or delay what's already on screen.
     */
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    /** Updates the query and (debounced) runs the search; blank query resets to [SearchUiState.Idle]. */
    fun onQueryChange(raw: String) {
        _query.value = raw
        runSearch(raw, debounce = true)
        runSuggest(raw)
    }

    /** Runs [suggestion] as the query and closes the list — the user has chosen, so stop suggesting. */
    fun applySuggestion(suggestion: String) {
        _query.value = suggestion
        _suggestions.value = emptyList()
        suggestJob?.cancel()
        runSearch(suggestion, debounce = false)
    }

    /** Dismisses the suggestions without touching the query (submitting, or tapping away). */
    fun dismissSuggestions() {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
    }

    private fun runSuggest(raw: String) {
        suggestJob?.cancel()
        val q = raw.trim()
        if (q.length < MIN_SUGGEST_CHARS) {
            _suggestions.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            // Never a failure the user sees: no suggestions is a perfectly good outcome.
            val results = runCatching {
                withContext(io) { youtube.suggestions(q, MAX_SUGGESTIONS) }
            }.getOrDefault(emptyList())
            // The query may have moved on while this was in flight.
            if (_query.value.trim() == q) _suggestions.value = results
        }
    }

    /** Switches source tab and re-runs the current query against it (no debounce — it's a deliberate tap). */
    fun selectTab(tab: SearchTab) {
        if (tab == _tab.value) return
        _tab.value = tab
        runSearch(_query.value, debounce = false)
    }

    /** Re-runs the current query immediately (used by the Offline/Error "Retry" action). */
    fun retry() = runSearch(_query.value, debounce = false)

    private fun runSearch(raw: String, debounce: Boolean) {
        searchJob?.cancel()
        if (raw.isBlank()) {
            _uiState.value = SearchUiState.Idle
            return
        }
        val tab = _tab.value
        searchJob = viewModelScope.launch {
            if (debounce) delay(DEBOUNCE_MS)
            _uiState.value = SearchUiState.Loading
            try {
                val results = when (tab) {
                    SearchTab.Songs -> searchMusic(raw)
                    SearchTab.Artists -> searchMusic(raw, listOf(SearchCategory.ARTISTS))
                    SearchTab.Albums -> searchMusic(raw, listOf(SearchCategory.ALBUMS))
                    SearchTab.Playlists -> playlistSources.search(raw)
                    SearchTab.Underground -> streamingSources.search(raw)
                }
                _uiState.value =
                    if (results.isEmpty) SearchUiState.Empty else SearchUiState.Results(results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Network) {
                _uiState.value = SearchUiState.Offline
            } catch (e: Exception) {
                _uiState.value = SearchUiState.Error(e.toSafeMessage("Something went wrong"))
            }
        }
    }

    fun clear() = onQueryChange("")

    private companion object {
        const val DEBOUNCE_MS = 300L

        /** Shorter than the search debounce: a hint that arrives after the results is useless. */
        const val SUGGEST_DEBOUNCE_MS = 180L

        /** One or two letters match everything; suggesting then is noise. */
        const val MIN_SUGGEST_CHARS = 2

        /** Deliberately few — this is a hint under the field, not a screen of its own. */
        const val MAX_SUGGESTIONS = 5
    }
}
