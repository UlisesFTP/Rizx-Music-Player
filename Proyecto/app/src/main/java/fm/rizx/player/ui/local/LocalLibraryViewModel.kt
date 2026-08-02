package fm.rizx.player.ui.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.domain.model.PlaylistSummary
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueSourceKind
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.repository.LocalLibraryRepository
import fm.rizx.player.domain.repository.LocalSong
import fm.rizx.player.domain.repository.OpenedFilesRepository
import fm.rizx.player.domain.repository.PlaylistRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A local album grouped from the scan. [id] is the raw MediaStore album id (for navigation). */
data class LocalAlbum(val id: String, val title: String, val artist: String, val artworkUrl: String?, val trackCount: Int)

/** A local artist grouped from the scan. [id] is the raw MediaStore artist id. */
data class LocalArtist(val id: String, val name: String, val trackCount: Int, val albumCount: Int)

/** How the Songs list is ordered. TITLE is the default and the only order the A–Z rail applies to. */
enum class LocalSort { TITLE, RECENT, ARTIST, DURATION }

/** The header's one line of truth about the scan: how many songs, how long, how heavy. */
data class LocalStats(val songCount: Int, val totalDurationMs: Long, val totalSizeBytes: Long)

/**
 * Backs the on-device music player (Songs / Playlists / Albums / Artists / Files) and its album/artist
 * detail. Songs come from [LocalLibraryRepository]'s scan; albums and artists are grouped from them in
 * memory; playlists are the user's **own** (imports filtered out); Files is everything opened through
 * the system picker. Playing anything sets the whole visible list as the queue context so next/prev
 * traverse it, like every other source.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class LocalLibraryViewModel @Inject constructor(
    private val library: LocalLibraryRepository,
    private val playback: PlaybackController,
    private val playlistRepository: PlaylistRepository,
    private val openedFiles: OpenedFilesRepository,
    private val favorites: fm.rizx.player.domain.repository.FavoritesRepository,
) : ViewModel() {

    val songs: StateFlow<List<Track>> = library.songs

    /** Liked identity keys, so every local row can draw its heart from one set (not N flows). */
    val likedKeys: StateFlow<Set<String>> =
        favorites.favoriteTracks()
            .map { tracks -> tracks.mapTo(HashSet()) { it.source.identityKey } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggleFavorite(track: Track) {
        viewModelScope.launch { runCatching { favorites.toggleTrack(track) } }
    }

    private val _sort = MutableStateFlow(LocalSort.TITLE)
    val sort: StateFlow<LocalSort> = _sort.asStateFlow()

    /** The Songs list in the chosen order — what the screen shows and what [playAll] queues. */
    val sortedSongs: StateFlow<List<Track>> =
        combine(library.scan, _sort) { scan, sort -> sortScan(scan, sort) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** identityKey → codec badge (FLAC/WAV only): lossless is worth a stamp, lossy would be noise. */
    val losslessBadges: StateFlow<Map<String, String>> =
        library.scan.map { scan ->
            buildMap {
                scan.forEach { row ->
                    val codec = fm.rizx.player.domain.model.codecForMime(row.mimeType)
                    if (codec == "FLAC" || codec == "WAV") put(row.track.source.identityKey, codec)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val stats: StateFlow<LocalStats> =
        library.scan.map { scan ->
            LocalStats(
                songCount = scan.size,
                totalDurationMs = scan.sumOf { it.track.durationMs ?: 0L },
                totalSizeBytes = scan.sumOf { it.sizeBytes },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalStats(0, 0L, 0L))

    val albums: StateFlow<List<LocalAlbum>> =
        library.songs.map(::groupAlbums).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<LocalArtist>> =
        library.songs.map(::groupArtists).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The user's own playlists — the ones made here, not the imported copies of someone else's. */
    val ownPlaylists: StateFlow<List<PlaylistSummary>> =
        playlistRepository.playlists()
            .map { lists -> lists.filterNot { it.isImported } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Files opened through the system picker, newest first. */
    val openedRecent: StateFlow<List<Track>> = openedFiles.recent

    /** How many audios a just-opened folder left out (over the cap); 0 clears the notice. */
    private val _folderSkipped = MutableStateFlow(0)
    val folderSkipped: StateFlow<Int> = _folderSkipped.asStateFlow()

    /** Change ticks from MediaStore; debounced so a 200-file copy is one rescan, not two hundred. */
    private val changeTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        viewModelScope.launch {
            changeTicks.debounce(REFRESH_DEBOUNCE_MS).collect { library.refresh() }
        }
    }

    fun setSort(sort: LocalSort) {
        _sort.value = sort
    }

    /** (Re)scans the device. Call once the audio permission is granted. */
    fun refresh() {
        viewModelScope.launch { library.refresh() }
    }

    /** Starts watching MediaStore for changes; returns the unregister handle for the screen's dispose. */
    fun startObserving(): () -> Unit = library.observeChanges { changeTicks.tryEmit(Unit) }

    /**
     * Play the on-device songs from [index]. [tracks] is what the Songs view is showing — narrowed by its
     * filter and ordered by its sort — so next/prev stay inside exactly what the user is looking at.
     */
    fun playAll(index: Int, tracks: List<Track> = sortedSongs.value) = play(tracks, index, "Local music")

    /** The whole visible list, shuffled, from its first song — the header's Shuffle button. */
    fun shuffleAll(tracks: List<Track> = sortedSongs.value) {
        if (tracks.isEmpty()) return
        play(tracks.shuffled(), 0, "Local music")
    }

    /** Play one album's tracks from [index]. */
    fun playAlbum(albumId: String, index: Int) {
        val tracks = albumTracks(albumId)
        play(tracks, index, tracks.firstOrNull()?.album?.title ?: "Album")
    }

    fun shuffleAlbum(albumId: String) {
        val tracks = albumTracks(albumId).shuffled()
        if (tracks.isNotEmpty()) play(tracks, 0, tracks.first().album?.title ?: "Album")
    }

    /** Play one artist's tracks from [index]. */
    fun playArtist(artistId: String, index: Int) {
        val tracks = artistTracks(artistId)
        play(tracks, index, tracks.firstOrNull()?.artists?.firstOrNull()?.name ?: "Artist")
    }

    fun shuffleArtist(artistId: String) {
        val tracks = artistTracks(artistId).shuffled()
        if (tracks.isNotEmpty()) play(tracks, 0, tracks.first().artists.firstOrNull()?.name ?: "Artist")
    }

    /** The songs of a local album, in track-number order. */
    fun albumTracks(albumId: String): List<Track> =
        songs.value.filter { it.album?.source?.id == "album:$albumId" }
            .sortedBy { it.trackNumber ?: Int.MAX_VALUE }

    /** The songs of a local artist, by title. */
    fun artistTracks(artistId: String): List<Track> =
        songs.value.filter { track -> track.artists.any { it.source?.id == "artist:$artistId" } }

    /** The albums of one local artist — the artist detail's shelf. */
    fun artistAlbums(artistId: String): List<LocalAlbum> {
        val theirs = artistTracks(artistId)
        return groupAlbums(theirs)
    }

    // ---- the file explorer ----

    /** Resolves the picked documents and plays them as a queue (also lands them in Recientes). */
    fun openAndPlayFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val tracks = openedFiles.openFiles(uris)
            if (tracks.isNotEmpty()) play(tracks, 0, tracks.first().title)
        }
    }

    /** Walks the picked folder and plays its audio in natural order; reports what fell over the cap. */
    fun openAndPlayFolder(treeUri: String) {
        viewModelScope.launch {
            val folder = openedFiles.openFolder(treeUri)
            _folderSkipped.value = folder.skipped
            if (folder.tracks.isNotEmpty()) play(folder.tracks, 0, folder.name)
        }
    }

    fun dismissFolderNotice() {
        _folderSkipped.value = 0
    }

    /** Plays one remembered file, with the whole Recientes list as its queue. */
    fun playOpened(index: Int) {
        val tracks = openedRecent.value
        if (index in tracks.indices) play(tracks, index, "Files")
    }

    fun forgetOpened(track: Track) {
        viewModelScope.launch { openedFiles.forget(track) }
    }

    /** Creates a playlist from the local Playlists view. The list refreshes itself via the flow. */
    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { runCatching { playlistRepository.createPlaylist(trimmed) } }
    }

    private fun play(tracks: List<Track>, index: Int, label: String) {
        if (tracks.isEmpty()) return
        playback.playContext(tracks, index, QueueContext(kind = QueueSourceKind.LOCAL, label = label))
    }

    companion object {
        const val REFRESH_DEBOUNCE_MS = 1_500L

        /** Pure, so the orderings unit-test on the JVM. Ties broken by title so the order is stable. */
        fun sortScan(scan: List<LocalSong>, sort: LocalSort): List<Track> = when (sort) {
            LocalSort.TITLE -> scan.sortedBy { it.track.title.lowercase() }
            LocalSort.RECENT -> scan.sortedWith(
                compareByDescending<LocalSong> { it.dateAddedSec }.thenBy { it.track.title.lowercase() },
            )
            LocalSort.ARTIST -> scan.sortedWith(
                compareBy<LocalSong> { it.track.artists.firstOrNull()?.name?.lowercase() ?: "￿" }
                    .thenBy { it.track.title.lowercase() },
            )
            LocalSort.DURATION -> scan.sortedWith(
                compareByDescending<LocalSong> { it.track.durationMs ?: 0L }.thenBy { it.track.title.lowercase() },
            )
        }.map { it.track }

        fun groupAlbums(songs: List<Track>): List<LocalAlbum> =
            songs.filter { it.album != null }
                .groupBy { it.album!!.source.id } // "album:<id>"
                .map { (key, tracks) ->
                    val album = tracks.first().album!!
                    LocalAlbum(
                        id = key.substringAfter(':'),
                        title = album.title,
                        artist = tracks.firstNotNullOfOrNull { it.artists.firstOrNull()?.name }.orEmpty(),
                        artworkUrl = album.artwork.coverUrl(),
                        trackCount = tracks.size,
                    )
                }
                .sortedBy { it.title.lowercase() }

        fun groupArtists(songs: List<Track>): List<LocalArtist> =
            songs.filter { it.artists.firstOrNull()?.source != null }
                .groupBy { it.artists.first().source!!.id } // "artist:<id>"
                .map { (key, tracks) ->
                    LocalArtist(
                        id = key.substringAfter(':'),
                        name = tracks.first().artists.first().name,
                        trackCount = tracks.size,
                        albumCount = tracks.mapNotNull { it.album?.source?.id }.distinct().size,
                    )
                }
                .sortedBy { it.name.lowercase() }
    }
}
