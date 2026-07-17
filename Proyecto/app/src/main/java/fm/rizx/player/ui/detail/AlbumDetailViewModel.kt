package fm.rizx.player.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.error.AppError
import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.DownloadState
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueSourceKind
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.repository.DownloadRepository
import fm.rizx.player.domain.repository.MetadataRepository
import fm.rizx.player.domain.repository.QueueRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI state for the album detail screen. */
sealed interface AlbumUiState {
    data object Loading : AlbumUiState
    data class Content(val album: Album) : AlbumUiState
    data object Offline : AlbumUiState
    data class Error(val message: String) : AlbumUiState
}

/**
 * Loads a full [Album] by the `ProviderRef` (provider + id) from the nav args, via the active metadata
 * provider. Playing a track routes through the queue + controller (full-length via Audius when active).
 */
@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val metadata: MetadataRepository,
    private val queue: QueueRepository,
    private val playback: PlaybackController,
    private val downloads: DownloadRepository,
) : ViewModel() {

    private val source = ProviderRef(
        provider = checkNotNull(savedStateHandle["provider"]),
        id = checkNotNull(savedStateHandle["id"]),
    )

    private val _state = MutableStateFlow<AlbumUiState>(AlbumUiState.Loading)
    val state: StateFlow<AlbumUiState> = _state.asStateFlow()

    val downloadStates: StateFlow<Map<String, DownloadState>> = downloads.states

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = AlbumUiState.Loading
            _state.value = try {
                metadata.albumDetail(source)?.let { AlbumUiState.Content(it) }
                    ?: AlbumUiState.Error("Album not found")
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Network) {
                AlbumUiState.Offline
            } catch (e: Exception) {
                AlbumUiState.Error(e.message ?: "Couldn't load album")
            }
        }
    }

    /** Play the album from track [index] — the whole album becomes the queue so next/prev traverse it. */
    fun play(index: Int) {
        val album = (_state.value as? AlbumUiState.Content)?.album ?: return
        playback.playContext(album.tracks, index, QueueContext(kind = QueueSourceKind.ALBUM, label = album.title))
    }

    fun downloadTrack(track: Track) = downloads.download(track)

    fun cancelDownload(key: String) = downloads.cancel(key)

    /** Saves the whole album for offline listening. Already-downloaded tracks are skipped. */
    fun downloadAll() {
        downloads.downloadAll((_state.value as? AlbumUiState.Content)?.album?.tracks.orEmpty())
    }
}
