package fm.rizx.player.ui.station

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.error.AppError
import fm.rizx.player.core.error.toSafeMessage
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueSourceKind
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.repository.DashboardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI state for a mood/genre station. */
sealed interface StationUiState {
    data object Loading : StationUiState
    data class Content(val tracks: List<Track>) : StationUiState
    data object Empty : StationUiState
    data object Offline : StationUiState
    data class Error(val message: String) : StationUiState
}

/**
 * What a station is playing **right now**.
 *
 * The station is a live rotation, not a fixed playlist — two consecutive fetches of the same station
 * share only about half their tracks (verified against the API). So this shows the list it actually
 * fetched and plays *that* list; reopening the station legitimately shows a different one.
 *
 * Resolution is routed by the provider id the station arrived with, since only the provider that
 * published a station can turn it back into tracks.
 */
@HiltViewModel
class StationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dashboard: DashboardRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    private val providerId: String = savedStateHandle.get<String>("provider").orEmpty()
    private val stationId: String = savedStateHandle.get<String>("id").orEmpty()

    /** Title and cover ride the route, so the header is complete before the tracks land. */
    val stationName: String = savedStateHandle.get<String>("name")?.takeIf { it.isNotBlank() }.orEmpty()
    val artworkUrl: String? = savedStateHandle.get<String>("art")?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow<StationUiState>(StationUiState.Loading)
    val state: StateFlow<StationUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = StationUiState.Loading
            _state.value = try {
                val tracks = dashboard.stationTracks(providerId, stationId, STATION_TRACKS)
                if (tracks.isEmpty()) StationUiState.Empty else StationUiState.Content(tracks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Network) {
                StationUiState.Offline
            } catch (e: Exception) {
                StationUiState.Error(e.toSafeMessage("Couldn't load this station"))
            }
        }
    }

    /**
     * Play from [index]; the fetched list becomes the queue, labeled the way the grid used to label
     * it ("Station · Chill Out"), so next/previous walk the station instead of wandering into a radio.
     */
    fun play(index: Int, queueLabel: String) {
        val tracks = (_state.value as? StationUiState.Content)?.tracks ?: return
        if (index !in tracks.indices) return
        playback.playContext(tracks, index, QueueContext(kind = QueueSourceKind.PLAYLIST, label = queueLabel))
    }

    private companion object {
        /** What one visit to a station offers. The rotation refills it on the next visit. */
        const val STATION_TRACKS = 50
    }
}
