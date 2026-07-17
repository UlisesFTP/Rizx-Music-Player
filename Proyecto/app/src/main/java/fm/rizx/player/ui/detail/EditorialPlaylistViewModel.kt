package fm.rizx.player.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.error.AppError
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueSourceKind
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.repository.PlaylistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI state for an editorial (remote, read-only) playlist. */
sealed interface EditorialPlaylistUiState {
    data object Loading : EditorialPlaylistUiState
    data class Content(val tracks: List<Track>) : EditorialPlaylistUiState
    data object Offline : EditorialPlaylistUiState
    data class Error(val message: String) : EditorialPlaylistUiState
}

/**
 * Loads a **pre-made / remote** playlist's tracks — a Deezer editorial playlist off the Home feed, or a
 * Deezer/YouTube playlist opened from search — by its `ProviderRef` + name from the nav args, via
 * [previewPlaylist][PlaylistRepository.previewPlaylist] (which reconstructs the source URL and routes to
 * the matching playlist provider). Playing a track sets the whole playlist as the queue context so
 * next/prev traverse it — the same primitive as own playlists.
 */
@HiltViewModel
class EditorialPlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlists: PlaylistRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    private val source = ProviderRef(
        provider = checkNotNull(savedStateHandle["provider"]),
        id = checkNotNull(savedStateHandle["id"]),
    )

    /** The playlist name, passed through the nav route (used for the header + the queue-context label). */
    val playlistName: String = savedStateHandle.get<String>("name")?.takeIf { it.isNotBlank() } ?: "Playlist"

    private val _state = MutableStateFlow<EditorialPlaylistUiState>(EditorialPlaylistUiState.Loading)
    val state: StateFlow<EditorialPlaylistUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = EditorialPlaylistUiState.Loading
            _state.value = try {
                val tracks = playlists.previewPlaylist(source)
                if (tracks.isEmpty()) EditorialPlaylistUiState.Error("This playlist is empty or unavailable")
                else EditorialPlaylistUiState.Content(tracks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Network) {
                EditorialPlaylistUiState.Offline
            } catch (e: Exception) {
                EditorialPlaylistUiState.Error(e.message ?: "Couldn't load playlist")
            }
        }
    }

    /** Play the playlist from track [index] — the whole playlist becomes the queue for next/prev. */
    fun play(index: Int) {
        val tracks = (_state.value as? EditorialPlaylistUiState.Content)?.tracks ?: return
        playback.playContext(tracks, index, QueueContext(kind = QueueSourceKind.PLAYLIST, label = playlistName))
    }
}
