package fm.rizx.player.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.error.AppError
import fm.rizx.player.core.error.toSafeMessage
import fm.rizx.player.data.local.store.SearchHistoryStore
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.search.PlaylistSourcesSearch
import fm.rizx.player.data.search.StreamingSourcesSearch
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchCategory
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.repository.RecentlyPlayedRepository
import fm.rizx.player.domain.usecase.ArtistNameMatching
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
import kotlinx.coroutines.flow.combine
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
 * One row of the suggestion list under the field.
 *
 * [recent] rows are the user's own past searches - matched locally, shown first, and drawn with a
 * history glyph instead of a magnifier so the two are never confused.
 */
data class Suggestion(val text: String, val recent: Boolean)

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
    private val history: SearchHistoryStore,
    recents: RecentlyPlayedRepository,
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

    /**
     * What the idle screen's pills offer, in order: **the searches you actually made**, then the artists you
     * play most, then the curated names the app ships with.
     *
     * Entirely local - history, recents and likes are all on-device - so the pills are on screen with the
     * rest of the idle state and can never arrive late enough to move it. "Similar artists" comes for free
     * in the second tier: what fills the play history *is* the recommendation engine's output, so an artist
     * a mix or a radio surfaced and you played is already there, with no lookup of its own.
     *
     * Names are folded through [ArtistNameMatching.key] before deduping, so "The Weeknd" searched and
     * "the weeknd" played are one pill rather than two.
     */
    val pills: StateFlow<List<Suggestion>> =
        combine(history.queries(), recents.recent(TASTE_ITEMS), favorites.favoriteTracks()) { searched, played, liked ->
            buildPills(searched, played + liked)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Likes or unlikes [track] (the heart on a search result). */
    fun toggleFavorite(track: Track) {
        viewModelScope.launch { favorites.toggleTrack(track) }
    }

    /** What the network last returned for the query being typed. */
    private val _remote = MutableStateFlow<List<String>>(emptyList())

    /** Whether the list is showing at all - a submit or a pick closes it without clearing anything. */
    private val _open = MutableStateFlow(false)

    /**
     * Autocomplete for what's being typed: **matching past searches first**, then the live suggestions.
     * Kept apart from [uiState] on purpose - suggestions are a hint about the *query*, not a result, and
     * they must never replace or delay what's already on screen.
     *
     * Capped at [MAX_SUGGESTIONS] **including** the recent rows, so the list is exactly as tall as it was
     * before they existed: past searches take the first two slots rather than adding two.
     */
    val suggestions: StateFlow<List<Suggestion>> =
        combine(_query, _open, history.queries(), _remote) { query, open, past, remote ->
            if (open) mergeSuggestions(query, past, remote) else emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    /** Updates the query and (debounced) runs the search; blank query resets to [SearchUiState.Idle]. */
    fun onQueryChange(raw: String) {
        _query.value = raw
        _open.value = raw.isNotBlank()
        runSearch(raw, debounce = true)
        runSuggest(raw)
    }

    /**
     * A **deliberate** search: the keyboard's Search key, a tapped suggestion, a tapped pill or genre tile.
     *
     * These and only these are remembered. The debounced query behind every keystroke is not - recording
     * that would bury the one search the user meant under every prefix of it.
     */
    fun searchFor(text: String) {
        _query.value = text
        _open.value = false
        suggestJob?.cancel()
        _remote.value = emptyList()
        val query = text.trim()
        if (query.isNotEmpty()) viewModelScope.launch { history.remember(query) }
        runSearch(text, debounce = false)
    }

    /** The keyboard's Search key: commit whatever is in the field. */
    fun submit() = searchFor(_query.value)

    /** Runs [suggestion] as the query and closes the list. */
    fun applySuggestion(suggestion: String) = searchFor(suggestion)

    /** Dismisses the suggestions without touching the query (tapping away). */
    fun dismissSuggestions() {
        suggestJob?.cancel()
        _open.value = false
    }

    private fun runSuggest(raw: String) {
        suggestJob?.cancel()
        val q = raw.trim()
        if (q.length < MIN_SUGGEST_CHARS) {
            // Not an early exit from suggesting altogether: one character is too little for the network to
            // say anything useful, but plenty for a past search to match on.
            _remote.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            // Never a failure the user sees: no suggestions is a perfectly good outcome.
            val results = runCatching {
                withContext(io) { youtube.suggestions(q, MAX_SUGGESTIONS) }
            }.getOrDefault(emptyList())
            // The query may have moved on while this was in flight.
            if (_query.value.trim() == q) _remote.value = results
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

    /**
     * Past searches that **complete** what is being typed, then the network's suggestions, deduped and cut
     * to one list's worth of rows.
     *
     * A past search equal to the query is dropped: offering "the weeknd" while "the weeknd" is in the field
     * is a row that does nothing. Prefix rather than substring, for the same reason the autocomplete is a
     * prefix - a row that does not extend what you typed reads as a mistake.
     */
    private fun mergeSuggestions(query: String, past: List<String>, remote: List<String>): List<Suggestion> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val recent = past.asSequence()
            .filter { it.length > q.length && it.startsWith(q, ignoreCase = true) }
            .take(MAX_RECENT_ROWS)
            .map { Suggestion(it, recent = true) }
            .toList()
        val seen = recent.mapTo(mutableSetOf()) { it.text.lowercase() }
        val rest = remote.asSequence()
            .filter { seen.add(it.lowercase()) }
            .map { Suggestion(it, recent = false) }
        return (recent.asSequence() + rest).take(MAX_SUGGESTIONS).toList()
    }

    /** See [pills]. */
    private fun buildPills(searched: List<String>, taste: List<Track>): List<Suggestion> {
        val out = LinkedHashMap<String, Suggestion>()
        fun offer(text: String, recent: Boolean) {
            if (out.size >= MAX_PILLS || text.isBlank()) return
            val key = ArtistNameMatching.key(text).ifEmpty { text.trim().lowercase() }
            out.putIfAbsent(key, Suggestion(text.trim(), recent))
        }
        searched.take(MAX_SEARCHED_PILLS).forEach { offer(it, recent = true) }
        topArtists(taste).forEach { offer(it, recent = false) }
        FALLBACK_PILLS.forEach { offer(it, recent = false) }
        return out.values.toList()
    }

    /** The artists credited in [taste], most-played first. Ties keep play order, so this is stable. */
    private fun topArtists(taste: List<Track>): List<String> {
        val counts = LinkedHashMap<String, Int>()
        val names = LinkedHashMap<String, String>()
        taste.forEach { track ->
            track.artists.asSequence().map { it.name }.filter { it.isNotBlank() }.forEach { name ->
                val key = ArtistNameMatching.key(name)
                if (key.isEmpty()) return@forEach
                counts[key] = (counts[key] ?: 0) + 1
                // Shortest spelling wins, so a channel credit never names the pill (see MixBuilder).
                val known = names[key]
                if (known == null || name.length < known.length) names[key] = name
            }
        }
        return counts.entries.sortedByDescending { it.value }.mapNotNull { names[it.key] }
    }

    private companion object {
        const val DEBOUNCE_MS = 300L

        /** Shorter than the search debounce: a hint that arrives after the results is useless. */
        const val SUGGEST_DEBOUNCE_MS = 180L

        /** One or two letters match everything; suggesting then is noise. */
        const val MIN_SUGGEST_CHARS = 2

        /** Deliberately few — this is a hint under the field, not a screen of its own. */
        const val MAX_SUGGESTIONS = 5

        /** Of those rows, how many a past search may take. Two, so the live suggestions still get three. */
        const val MAX_RECENT_ROWS = 2

        /** How many pills the idle screen shows, and how many of them the user's own searches may fill. */
        const val MAX_PILLS = 8
        const val MAX_SEARCHED_PILLS = 5

        /** How deep the play history is read for artist names. */
        const val TASTE_ITEMS = 30

        /**
         * What the pills show before there is anything personal to show - a first launch. Names, so nothing
         * here is localized; they are replaced pill by pill as the user searches and plays.
         */
        val FALLBACK_PILLS = listOf("Daft Punk", "The Weeknd", "Coldplay", "Bad Bunny", "Lo-fi beats", "Tame Impala")
    }
}
