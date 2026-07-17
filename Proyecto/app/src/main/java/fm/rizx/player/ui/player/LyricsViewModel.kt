package fm.rizx.player.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.error.AppError
import fm.rizx.player.domain.repository.LyricsRepository
import fm.rizx.player.domain.repository.QueueRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** UI state for the lyrics of the currently-playing track. */
sealed interface LyricsUiState {
    data object NoTrack : LyricsUiState
    data object Loading : LyricsUiState
    data class Text(val title: String, val artist: String, val lyrics: String) : LyricsUiState
    data class Empty(val title: String) : LyricsUiState
    data object Offline : LyricsUiState
    data class Error(val message: String) : LyricsUiState
}

/**
 * Fetches lyrics for the current queue item, re-fetching whenever the current track changes
 * ([flatMapLatest] cancels the previous lookup). No lyrics / no provider → [LyricsUiState.Empty];
 * connectivity failures → [LyricsUiState.Offline]; the app never crashes on a lyrics error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LyricsViewModel @Inject constructor(
    queue: QueueRepository,
    private val lyrics: LyricsRepository,
) : ViewModel() {

    val state: StateFlow<LyricsUiState> = queue.state
        .map { it.current?.track }
        .distinctUntilChanged { a, b -> a?.source == b?.source }
        .flatMapLatest { track ->
            flow {
                if (track == null) {
                    emit(LyricsUiState.NoTrack)
                    return@flow
                }
                emit(LyricsUiState.Loading)
                val artist = track.artists.joinToString { it.name }.ifEmpty { "—" }
                try {
                    val text = lyrics.lyricsFor(track)
                    emit(
                        if (text.isNullOrBlank()) LyricsUiState.Empty(track.title)
                        else LyricsUiState.Text(track.title, artist, text),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AppError.Network) {
                    emit(LyricsUiState.Offline)
                } catch (e: Exception) {
                    emit(LyricsUiState.Error(e.message ?: "Couldn't load lyrics"))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsUiState.NoTrack)
}
