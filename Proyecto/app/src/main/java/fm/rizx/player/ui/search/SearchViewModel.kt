package fm.rizx.player.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.search.PlaylistSourcesSearch
import fm.rizx.player.data.search.StreamingSourcesSearch
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchCategory
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.usecase.SearchMusicUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
) : ViewModel() {

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

    private var searchJob: Job? = null

    /** Updates the query and (debounced) runs the search; blank query resets to [SearchUiState.Idle]. */
    fun onQueryChange(raw: String) {
        _query.value = raw
        runSearch(raw, debounce = true)
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
                _uiState.value = SearchUiState.Error(e.message ?: "Something went wrong")
            }
        }
    }

    fun clear() = onQueryChange("")

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
