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

    init {
        // Repairs covers that the import couldn't supply (Spotify ships no per-track images, and playlists
        // imported before cover support have none at all). A no-op once everything already has artwork, and
        // the observed Flow above picks the result up on its own.
        viewModelScope.launch { playlists.backfillArtwork(playlistId) }
    }

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

    /**
     * Play from item [index] — the playlist becomes the queue for next/prev.
     *
     * [tracks] is what the screen is showing: identical to the playlist unless its filter is narrowing it,
     * in which case the queue is the rows the user can actually see.
     */
    fun play(index: Int, tracks: List<Track> = playlist.value?.items.orEmpty().map { it.track }) {
        val pl = playlist.value ?: return
        if (tracks.isEmpty()) return
        playback.playContext(tracks, index, QueueContext(kind = QueueSourceKind.PLAYLIST, label = pl.name))
    }

    fun downloadTrack(track: Track) = downloads.download(track)

    fun cancelDownload(key: String) = downloads.cancel(key)

    /**
     * Saves the playlist for offline listening. Already-downloaded ones are skipped. [tracks] defaults to
     * the whole playlist and is the visible subset while the filter narrows it — "all" means all of what
     * the button is sitting next to, which is also what its own done/total readout counts.
     */
    fun downloadAll(tracks: List<Track> = playlist.value?.items.orEmpty().map { it.track }) {
        downloads.downloadAll(tracks)
    }

    /** JSON export of this playlist for the file picker to write, or null if it no longer exists. */
    suspend fun exportJson(): String? = playlists.exportPlaylist(playlistId)
}
