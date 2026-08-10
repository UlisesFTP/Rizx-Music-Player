package fm.rizx.player.ui.genre

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.error.AppError
import fm.rizx.player.core.error.toSafeMessage
import fm.rizx.player.domain.model.GenreFeed
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueSourceKind
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.repository.DashboardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI state for a genre hub. */
sealed interface GenreUiState {
    data object Loading : GenreUiState
    data class Content(val feed: GenreFeed) : GenreUiState

    /** The provider answered, with nothing — a real outcome for a thin genre, not an error. */
    data object Empty : GenreUiState
    data object Offline : GenreUiState
    data class Error(val message: String) : GenreUiState
}

/**
 * Loads one genre's songs, playlists, artists and albums from the dashboard providers.
 *
 * The genre id arrives from the browse tile and is passed straight through — no query is ever built
 * from the genre's *name*, which is what made "Pop" return Pop Smoke.
 */
@HiltViewModel
class GenreViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dashboard: DashboardRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    private val genreId: String = savedStateHandle.get<String>("id").orEmpty()

    /** The localized label, passed through the route so the header can draw before the fetch lands. */
    val genreName: String = savedStateHandle.get<String>("name")?.takeIf { it.isNotBlank() }.orEmpty()

    private val _state = MutableStateFlow<GenreUiState>(GenreUiState.Loading)
    val state: StateFlow<GenreUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = GenreUiState.Loading
            _state.value = try {
                val feed = dashboard.genreFeed(genreId, FEED_LIMIT)
                if (feed.isEmpty) GenreUiState.Empty else GenreUiState.Content(feed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Network) {
                GenreUiState.Offline
            } catch (e: Exception) {
                GenreUiState.Error(e.toSafeMessage("Couldn't load this genre"))
            }
        }
    }

    /**
     * Play the genre's songs from [index]; the whole list becomes the queue, so next/prev stay inside
     * the genre. Recorded as a [QueueSourceKind.PLAYLIST] because that is what it behaves like — a
     * finite, ordered list the user opened — with the genre's own name as the label.
     */
    fun play(index: Int) {
        val tracks = (_state.value as? GenreUiState.Content)?.feed?.tracks ?: return
        if (index !in tracks.indices) return
        playback.playContext(tracks, index, QueueContext(kind = QueueSourceKind.PLAYLIST, label = genreName))
    }

    private companion object {
        /** Deep enough that the songs list is a genre to sit in, not a top-10 teaser. */
        const val FEED_LIMIT = 50
    }
}
