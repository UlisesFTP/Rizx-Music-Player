package fm.rizx.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.error.AppError
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.repository.DashboardRepository
import fm.rizx.player.domain.repository.QueueRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI state for the Home feed. */
sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Content(val feed: HomeFeed) : HomeUiState
    data object Offline : HomeUiState
    data class Error(val message: String) : HomeUiState
}

/**
 * Loads the real [HomeFeed] by fanning out over the active dashboard providers (Deezer charts).
 * A failing provider degrades gracefully in the repository; a total network failure surfaces as
 * [HomeUiState.Offline] with retry.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val dashboard: DashboardRepository,
    private val queue: QueueRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = HomeUiState.Loading
            _state.value = try {
                val feed = dashboard.homeFeed()
                if (feed.isEmpty) HomeUiState.Error("Nothing to show right now") else HomeUiState.Content(feed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Network) {
                HomeUiState.Offline
            } catch (e: Exception) {
                HomeUiState.Error(e.message ?: "Couldn't load Home")
            }
        }
    }

    /** A feed song starts a radio: play it now, then the service auto-fills similar tracks for next/prev. */
    fun playTrack(track: Track) {
        playback.playRadio(track)
    }
}
