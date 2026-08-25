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
import fm.rizx.player.domain.repository.PlaylistExportArtifact
import fm.rizx.player.domain.repository.PlaylistExportFormat
import fm.rizx.player.domain.repository.PlaylistExportRepository
import fm.rizx.player.domain.share.PlaylistShare
import fm.rizx.player.domain.share.PlaylistShareRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val exports: PlaylistExportRepository,
    private val shares: PlaylistShareRepository,
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

    data class ShareUiState(
        val isWorking: Boolean = false,
        val link: PlaylistShare? = null,
        val error: String? = null,
    )

    private val _shareState = MutableStateFlow(ShareUiState())
    val shareState: StateFlow<ShareUiState> = _shareState.asStateFlow()
    val cloudSharingConfigured: Boolean get() = shares.configured

    // Deliberately below _shareState: an implementation that answers without suspending would run this
    // body during construction, and the property it writes to would still be null.
    init { restoreShareLink() }

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

    suspend fun export(format: PlaylistExportFormat): PlaylistExportArtifact? = exports.export(playlistId, format)

    /**
     * Brings back the link this playlist already has, so leaving the screen doesn't lose it.
     *
     * Without this the sheet offers "create a link" to a playlist that already has a live one — the user
     * can neither copy it again nor revoke it, and tapping again mints a duplicate. Guarded against the
     * race with a link the user creates while the lookup is in flight: whatever they just made wins.
     */
    private fun restoreShareLink() {
        viewModelScope.launch {
            val restored = runCatching { shares.existing(playlistId) }.getOrNull() ?: return@launch
            _shareState.update { if (it.link == null && !it.isWorking) it.copy(link = restored) else it }
        }
    }

    fun createShareLink(captchaToken: String? = null) {
        if (_shareState.value.isWorking) return
        viewModelScope.launch {
            _shareState.update { it.copy(isWorking = true, error = null) }
            runCatching { shares.create(playlistId, captchaToken) }
                .onSuccess { link -> _shareState.value = ShareUiState(link = link) }
                .onFailure { error -> _shareState.value = ShareUiState(error = error.message ?: "No se pudo crear el enlace") }
        }
    }

    fun revokeShare() {
        val id = _shareState.value.link?.id ?: return
        viewModelScope.launch {
            _shareState.update { it.copy(isWorking = true, error = null) }
            runCatching { shares.revoke(id) }
                .onSuccess { _shareState.value = ShareUiState() }
                .onFailure { error -> _shareState.update { it.copy(isWorking = false, error = error.message) } }
        }
    }

    fun clearShareError() = _shareState.update { it.copy(error = null) }
}
