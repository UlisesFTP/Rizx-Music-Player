package fm.rizx.player.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.domain.model.DownloadState
import fm.rizx.player.domain.model.DownloadStatus
import fm.rizx.player.domain.model.DownloadedTrack
import fm.rizx.player.domain.model.PlaylistSummary
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueSourceKind
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.repository.DownloadRepository
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.repository.PlaylistRepository
import fm.rizx.player.domain.repository.QueueRepository
import fm.rizx.player.domain.repository.RecentlyPlayedRepository
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Library screen and the app-wide favorite/playlist actions. Favorite and playlist lists
 * are observed straight from their repositories (Room), so the UI updates immediately and survives
 * restart. Playing a library track routes through the queue + controller like everything else.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val favorites: FavoritesRepository,
    private val playlists: PlaylistRepository,
    private val recentlyPlayed: RecentlyPlayedRepository,
    private val queue: QueueRepository,
    private val playback: PlaybackController,
    private val downloads: DownloadRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val favoriteTracks: StateFlow<List<Track>> =
        favorites.favoriteTracks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Songs saved for offline listening, newest first. */
    val downloadedTracks: StateFlow<List<DownloadedTrack>> = downloads.downloads

    /** Per-track download state, keyed by `ProviderRef.identityKey` — backs every download button. */
    val downloadStates: StateFlow<Map<String, DownloadState>> = downloads.states

    val recentTracks: StateFlow<List<Track>> =
        recentlyPlayed.recent(25).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlistSummaries: StateFlow<List<PlaylistSummary>> =
        playlists.playlists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) viewModelScope.launch { playlists.createPlaylist(trimmed) }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch { playlists.deletePlaylist(id) }
    }

    fun unfavoriteTrack(track: Track) {
        viewModelScope.launch { favorites.removeTrack(track.source) }
    }

    /** Re-likes a track — backs the "undo" on an accidental unfavorite (the row vanishes on removal). */
    fun favoriteTrack(track: Track) {
        viewModelScope.launch { favorites.addTrack(track) }
    }

    fun addTrackToPlaylist(playlistId: String, track: Track) {
        viewModelScope.launch { playlists.addTracks(playlistId, listOf(track)) }
    }

    fun createPlaylistWithTrack(name: String, track: Track) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = playlists.createPlaylist(trimmed)
            playlists.addTracks(id, listOf(track))
        }
    }

    fun clearRecentlyPlayed() {
        viewModelScope.launch { recentlyPlayed.clear() }
    }

    /**
     * Imports a playlist file — a Rizx export, a Nuclear playlist, or an Exportify CSV. [fallbackName]
     * (normally the file name) names it when the format carries none. [onResult] carries the new id or
     * the failure.
     */
    fun importPlaylistFile(text: String, fallbackName: String? = null, onResult: (Result<String>) -> Unit = {}) {
        viewModelScope.launch {
            onResult(runCatching { playlists.importPlaylistFile(text, fallbackName) })
        }
    }

    /** Imports by URL (Deezer · Spotify · YouTube Music · a hosted file); [onResult] carries the id/failure. */
    fun importFromUrl(url: String, onResult: (Result<String>) -> Unit = {}) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            onResult(runCatching { playlists.importFromUrl(trimmed) })
        }
    }

    /**
     * Play the Liked-songs list from [index] — the list becomes the queue so next/prev traverse it.
     *
     * [tracks] is what the screen is *showing*. It only differs from the full list when the tab's filter is
     * narrowing it, and then the queue is what you filtered to: having next/prev walk rows the user cannot
     * see would make the filter a lie about what they were about to hear.
     */
    fun playLiked(index: Int, tracks: List<Track> = favoriteTracks.value) {
        if (tracks.isEmpty()) return
        playback.playContext(tracks, index, QueueContext(kind = QueueSourceKind.LIKED, label = "Liked songs"))
    }

    /** Play the Recently-played list from [index]; [tracks] is the visible list (see [playLiked]). */
    fun playRecent(index: Int, tracks: List<Track> = recentTracks.value) {
        if (tracks.isEmpty()) return
        playback.playContext(tracks, index, QueueContext(kind = QueueSourceKind.RECENTS, label = "Recently played"))
    }

    /** Play the Downloads list from [index] — the queue stays offline; [tracks] is the visible list. */
    fun playDownloads(index: Int, tracks: List<Track> = downloadedTracks.value.map { it.track }) {
        if (tracks.isEmpty()) return
        playback.playContext(tracks, index, QueueContext(kind = QueueSourceKind.DOWNLOADS, label = "Downloads"))
    }

    // ---- Downloads ----

    fun downloadTrack(track: Track) = downloads.download(track)

    /** The "Download as…" menu: this one download in an explicit format, ignoring the Settings default. */
    fun downloadTrackAs(track: Track, format: fm.rizx.player.domain.model.DownloadFormat) =
        downloads.download(track, format)

    fun downloadAll(tracks: List<Track>) = downloads.downloadAll(tracks)

    fun cancelDownload(key: String) = downloads.cancel(key)

    fun deleteDownload(key: String) {
        viewModelScope.launch { downloads.delete(key) }
    }

    fun deleteAllDownloads() {
        viewModelScope.launch { downloads.deleteAll() }
    }

    /** Copies a download into the shared `Music/Rizx` folder; [onResult] carries the name or the failure. */
    fun exportDownload(key: String, onResult: (Result<String>) -> Unit = {}) {
        viewModelScope.launch { onResult(downloads.export(key)) }
    }

    /**
     * Copies every download that isn't on the phone yet, one at a time, and reports how many made it.
     *
     * Sequential rather than parallel: each copy writes the index when it lands, and a dozen concurrent
     * writers would only take turns on that same lock while making the progress harder to reason about.
     */
    fun exportDownloads(entries: List<DownloadedTrack>, onDone: (saved: Int, failed: Int) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            var saved = 0
            var failed = 0
            entries.filter { it.exportedUri == null }.forEach { entry ->
                downloads.export(entry.key).fold(onSuccess = { saved++ }, onFailure = { failed++ })
            }
            onDone(saved, failed)
        }
    }

    // ---- Saving downloads to the phone ----

    /** Null until the user has been asked — see [SettingsRepository.saveDownloadsToPhone]. */
    val saveToPhone: StateFlow<Boolean?> =
        settings.saveDownloadsToPhone.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Whether to put the one-time question on screen: the user has never answered it **and** a download
     * is actually happening.
     *
     * Gated on in-flight work, not on the index: someone who already has downloads would otherwise be
     * asked the moment they open the app, about nothing they just did.
     */
    val askSaveToPhone: StateFlow<Boolean> =
        combine(settings.saveDownloadsToPhone, downloads.states) { answered, states ->
            answered == null && states.values.any { it.status in IN_FLIGHT }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setSaveToPhone(enabled: Boolean) {
        viewModelScope.launch { settings.setSaveDownloadsToPhone(enabled) }
    }

    private companion object {
        val IN_FLIGHT = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.CONVERTING)
    }
}
