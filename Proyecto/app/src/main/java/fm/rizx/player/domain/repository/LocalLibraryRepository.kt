package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * The on-device music library: the audio files scanned from `MediaStore`, plus the resolver hook that
 * plays them. Local tracks are ordinary [Track]s with `source = ProviderRef("local", "<MediaStore _ID>")`,
 * so favorites, playlists, the queue and recents handle them for free.
 */
/**
 * One scanned row: the [track] plus the file facts the library screens need and the domain [Track]
 * deliberately doesn't carry — when it landed on the device (sorting by recent), how big it is (the
 * header's total), and its mime (the format badge, and the honest codec claim at play time).
 */
data class LocalSong(
    val track: Track,
    val dateAddedSec: Long,
    val sizeBytes: Long,
    val mimeType: String?,
)

interface LocalLibraryRepository {

    /** All scanned on-device songs. Empty until [refresh] runs (which needs `READ_MEDIA_AUDIO`). */
    val songs: StateFlow<List<Track>>

    /** The same scan with its file facts — what the local screens read. Always in step with [songs]. */
    val scan: StateFlow<List<LocalSong>>

    /**
     * (Re)scans `MediaStore.Audio` into [songs]. Call after the audio permission is granted and to refresh.
     * Safe without permission — it just yields an empty list rather than throwing.
     */
    suspend fun refresh()

    /**
     * Watches the device's audio collection and calls [onChange] on any change (a file copied over USB, a
     * download landing). Returns the unregister handle; the caller owns debouncing and the re-[refresh].
     */
    fun observeChanges(onChange: () -> Unit): () -> Unit

    /**
     * The playable [Stream] for a local track (a `content://` MediaStore URI rebuilt from the track's id)
     * or a picker-opened file-track, else `null`. Called on the ExoPlayer loader thread, so it must stay
     * synchronous and non-blocking. Nothing is persisted; a vanished file simply fails to open.
     */
    fun localStream(track: Track): Stream?
}
