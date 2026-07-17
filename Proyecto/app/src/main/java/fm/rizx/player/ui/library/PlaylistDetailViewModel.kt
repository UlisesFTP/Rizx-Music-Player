package fm.rizx.player.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.domain.model.DownloadState
import fm.rizx.player.domain.model.Playlist
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueSourceKind
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.repository.DownloadRepository
import fm.rizx.player.domain.repository.PlaylistRepository
import fm.rizx.player.domain.repository.QueueRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the playlist detail screen. Observes the single [Playlist] by id (from the nav arg), so the
 * list reflects database changes live. Play routes a playlist track through the queue + controller.
 */
@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlists: PlaylistRepository,
    private val queue: QueueRepository,
    private val playback: PlaybackController,
    private val downloads: DownloadRepository,
) : ViewModel() {

    private val playlistId: String = checkNotNull(savedStateHandle["playlistId"])

    val playlist: StateFlow<Playlist?> =
        playlists.playlist(playlistId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val downloadStates: StateFlow<Map<String, DownloadState>> = downloads.states

    fun removeItem(itemId: String) {
        viewModelScope.launch { playlists.removeItem(playlistId, itemId) }
    }

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) viewModelScope.launch { playlists.rename(playlistId, trimmed, playlist.value?.description) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            playlists.deletePlaylist(playlistId)
            onDeleted()
        }
    }

    /** Play the playlist from item [index] — the whole playlist becomes the queue for next/prev. */
    fun play(index: Int) {
        val pl = playlist.value ?: return
        playback.playContext(pl.items.map { it.track }, index, QueueContext(kind = QueueSourceKind.PLAYLIST, label = pl.name))
    }

    fun downloadTrack(track: Track) = downloads.download(track)

    fun cancelDownload(key: String) = downloads.cancel(key)

    /** Saves every track in this playlist for offline listening. Already-downloaded ones are skipped. */
    fun downloadAll() {
        downloads.downloadAll(playlist.value?.items.orEmpty().map { it.track })
    }

    /** JSON export of this playlist for the file picker to write, or null if it no longer exists. */
    suspend fun exportJson(): String? = playlists.exportPlaylist(playlistId)
}
