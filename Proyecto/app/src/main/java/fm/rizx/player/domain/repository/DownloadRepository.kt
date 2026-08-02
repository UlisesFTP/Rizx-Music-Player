package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.DownloadFormat
import fm.rizx.player.domain.model.DownloadState
import fm.rizx.player.domain.model.DownloadedTrack
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the offline library: which tracks have audio on disk, and the queue that puts it there.
 *
 * **This is the authority on local files — a `Track`'s own `localFile` field is never consulted.** The
 * same track exists as separate JSON blobs in playlists, favorites, recently-played and the persisted
 * session; writing a local path into each copy would scatter the fact with no way to invalidate it when
 * the download is deleted (`stripResolutionState` deliberately preserves `localFile`, so it would
 * outlive the file forever). One keyed index instead, consulted at resolve time.
 *
 * Everything is keyed by `track.source.identityKey` — never `QueueItem.id` (re-minted per insertion),
 * and never `stream.source` (for a Deezer-discovered track played via Audius those are different
 * providers, so the download would be invisible from the playlist that asked for it).
 */
interface DownloadRepository {

    /** Tracks with audio on disk, newest first. Survives restarts. */
    val downloads: StateFlow<List<DownloadedTrack>>

    /** Per-track button state, keyed by identity: on-disk entries plus in-flight and failed transients. */
    val states: StateFlow<Map<String, DownloadState>>

    /**
     * A `file://` [Stream] for [track] if it is downloaded and the file is really there, else null.
     *
     * **Synchronous and non-blocking on purpose**: this is called from `QueueStreamResolver` on an
     * ExoPlayer loader thread, including outside its `runBlocking`. It is an in-memory map lookup plus a
     * `File.exists()` stat.
     *
     * Returning null *is* the fallback path: a deleted file, cleared app data, or an evicted download
     * simply makes the resolver do its normal network resolve. No error, nothing for the user to see.
     */
    fun localStream(track: Track): Stream?

    /**
     * Queues [track] for download. Already-downloaded or in-flight tracks are ignored.
     *
     * [format] overrides the Settings default for this one download (the "Download as…" menu); null —
     * every ordinary call site — means the user's configured [DownloadFormat].
     */
    fun download(track: Track, format: DownloadFormat? = null)

    /** Queues every not-yet-downloaded track in [tracks], in order. */
    fun downloadAll(tracks: List<Track>)

    /** Cancels a queued or in-flight download and removes its partial file. */
    fun cancel(key: String)

    /** Deletes the file and drops the index entry. The track stays in the library and streams again. */
    suspend fun delete(key: String)

    /** Deletes every download. */
    suspend fun deleteAll()

    /**
     * Drops a download whose file turned out to be unreadable, so playback falls back to the network.
     * Called from the playback error path — a corrupt local file would otherwise be preferred forever.
     */
    suspend fun markCorrupt(key: String)

    /** Copies a download into the shared `Music/Rizx` folder. Returns the display name it was saved as. */
    suspend fun export(key: String): Result<String>
}
