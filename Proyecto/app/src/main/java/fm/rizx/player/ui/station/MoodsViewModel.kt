package fm.rizx.player.ui.station

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.error.AppError
import fm.rizx.player.core.error.toSafeMessage
import fm.rizx.player.domain.model.MoodStation
import fm.rizx.player.domain.repository.DashboardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI state for the full mood/genre station list. */
sealed interface MoodsUiState {
    data object Loading : MoodsUiState

    /** Each station paired with the provider id able to resolve it. */
    data class Content(val stations: List<Pair<String, MoodStation>>) : MoodsUiState
    data object Empty : MoodsUiState
    data object Offline : MoodsUiState
    data class Error(val message: String) : MoodsUiState
}

/**
 * Every station the enabled providers publish — the "See all" behind Home's preview grid.
 *
 * Asks the repository for that one section rather than the whole feed: this screen wants stations,
 * and a Home feed's fan-out and blend to get them would be most of a Home load for a list of chips.
 */
@HiltViewModel
class MoodsViewModel @Inject constructor(
    private val dashboard: DashboardRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<MoodsUiState>(MoodsUiState.Loading)
    val state: StateFlow<MoodsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = MoodsUiState.Loading
            _state.value = try {
                val stations = dashboard.moodStations(ALL_STATIONS)
                    .flatMap { result -> result.items.map { result.providerId to it } }
                if (stations.isEmpty()) MoodsUiState.Empty else MoodsUiState.Content(stations)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Network) {
                MoodsUiState.Offline
            } catch (e: Exception) {
                MoodsUiState.Error(e.toSafeMessage("Couldn't load the stations"))
            }
        }
    }

    private companion object {
        /** Higher than any provider's catalogue — this screen is the inventory, not a preview. */
        const val ALL_STATIONS = 100
    }
}
