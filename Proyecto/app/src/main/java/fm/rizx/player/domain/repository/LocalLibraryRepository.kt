package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * The on-device music library: the audio files scanned from `MediaStore`, plus the resolver hook that
 * plays them. Local tracks are ordinary [Track]s with `source = ProviderRef("local", "<MediaStore _ID>")`,
 * so favorites, playlists, the queue and recents handle them for free.
 */
interface LocalLibraryRepository {

    /** All scanned on-device songs. Empty until [refresh] runs (which needs `READ_MEDIA_AUDIO`). */
    val songs: StateFlow<List<Track>>

    /**
     * (Re)scans `MediaStore.Audio` into [songs]. Call after the audio permission is granted and to refresh.
     * Safe without permission — it just yields an empty list rather than throwing.
     */
    suspend fun refresh()

    /**
     * The playable [Stream] for a local track (a `content://` MediaStore URI rebuilt from the track's id),
     * or `null` for any non-local track. Called on the ExoPlayer loader thread, so it must stay
     * synchronous and non-blocking. Nothing is persisted; a vanished file simply fails to open.
     */
    fun localStream(track: Track): Stream?
}
