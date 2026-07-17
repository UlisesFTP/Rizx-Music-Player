package fm.rizx.player.ui.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueSourceKind
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.repository.LocalLibraryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A local album grouped from the scan. [id] is the raw MediaStore album id (for navigation). */
data class LocalAlbum(val id: String, val title: String, val artist: String, val artworkUrl: String?, val trackCount: Int)

/** A local artist grouped from the scan. [id] is the raw MediaStore artist id. */
data class LocalArtist(val id: String, val name: String, val trackCount: Int, val albumCount: Int)

/**
 * Backs the on-device music library (Songs / Albums / Artists) and its album/artist detail. Songs come
 * straight from [LocalLibraryRepository]; albums and artists are grouped from them in memory. Playing
 * anything sets the whole list as the queue context so next/prev traverse it, like every other source.
 */
@HiltViewModel
class LocalLibraryViewModel @Inject constructor(
    private val library: LocalLibraryRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    val songs: StateFlow<List<Track>> = library.songs

    val albums: StateFlow<List<LocalAlbum>> =
        library.songs.map(::groupAlbums).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<LocalArtist>> =
        library.songs.map(::groupArtists).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** (Re)scans the device. Call once the audio permission is granted. */
    fun refresh() {
        viewModelScope.launch { library.refresh() }
    }

    /** Play all local songs from [index]. */
    fun playAll(index: Int) = play(songs.value, index, "Local music")

    /** Play one album's tracks from [index]. */
    fun playAlbum(albumId: String, index: Int) {
        val tracks = albumTracks(albumId)
        play(tracks, index, tracks.firstOrNull()?.album?.title ?: "Album")
    }

    /** Play one artist's tracks from [index]. */
    fun playArtist(artistId: String, index: Int) {
        val tracks = artistTracks(artistId)
        play(tracks, index, tracks.firstOrNull()?.artists?.firstOrNull()?.name ?: "Artist")
    }

    /** The songs of a local album, in track-number order. */
    fun albumTracks(albumId: String): List<Track> =
        songs.value.filter { it.album?.source?.id == "album:$albumId" }
            .sortedBy { it.trackNumber ?: Int.MAX_VALUE }

    /** The songs of a local artist, by title. */
    fun artistTracks(artistId: String): List<Track> =
        songs.value.filter { track -> track.artists.any { it.source?.id == "artist:$artistId" } }

    private fun play(tracks: List<Track>, index: Int, label: String) {
        if (tracks.isEmpty()) return
        playback.playContext(tracks, index, QueueContext(kind = QueueSourceKind.LOCAL, label = label))
    }

    private companion object {
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
