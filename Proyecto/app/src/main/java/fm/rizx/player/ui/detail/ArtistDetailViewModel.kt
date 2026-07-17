package fm.rizx.player.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.error.AppError
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueSourceKind
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.repository.MetadataRepository
import fm.rizx.player.domain.repository.QueueRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI state for the artist detail screen. */
sealed interface ArtistUiState {
    data object Loading : ArtistUiState
    data class Content(val artist: Artist) : ArtistUiState
    data object Offline : ArtistUiState
    data class Error(val message: String) : ArtistUiState
}

/** Loads a full [Artist] (top tracks + albums) by the nav-arg `ProviderRef` via the active provider. */
@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val metadata: MetadataRepository,
    private val queue: QueueRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    private val source = ProviderRef(
        provider = checkNotNull(savedStateHandle["provider"]),
        id = checkNotNull(savedStateHandle["id"]),
    )

    private val _state = MutableStateFlow<ArtistUiState>(ArtistUiState.Loading)
    val state: StateFlow<ArtistUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = ArtistUiState.Loading
            _state.value = try {
                metadata.artistDetail(source)?.let { ArtistUiState.Content(it) }
                    ?: ArtistUiState.Error("Artist not found")
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Network) {
                ArtistUiState.Offline
            } catch (e: Exception) {
                ArtistUiState.Error(e.message ?: "Couldn't load artist")
            }
        }
    }

    /** Play the artist's top tracks from [index] — the list becomes the queue so next/prev traverse it. */
    fun play(index: Int) {
        val artist = (_state.value as? ArtistUiState.Content)?.artist ?: return
        playback.playContext(artist.topTracks, index, QueueContext(kind = QueueSourceKind.ARTIST, label = artist.name))
    }
}
