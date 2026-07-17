package fm.rizx.player.data.local.store

import fm.rizx.player.domain.model.DownloadedTrack
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.stripResolutionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Persists the **download index**: which tracks have real audio on disk, and what that file is.
 *
 * One small JSON blob (atomic temp-then-rename, `Mutex`-serialized, every op `runCatching`-guarded) —
 * the same idiom as [PlaybackSessionStore], and for the same reason: a corrupt or missing file must
 * degrade to "nothing is downloaded" rather than break playback.
 *
 * The index is deliberately **not** a Room table. The lookup runs on an ExoPlayer loader thread from
 * `QueueStreamResolver`, which needs it synchronously and non-blocking; Room would force either a
 * `runBlocking` query on that thread or an in-memory mirror in front of it — and once there is a mirror,
 * the table is a second copy of the truth. The repository holds the map in a `StateFlow` (which the
 * Downloads tab needs anyway) and this store is just its durable backing.
 *
 * Only completed downloads are stored. QUEUED/DOWNLOADING/FAILED are in-flight state with nothing on
 * disk behind them, so they live in memory and die with the process.
 */
class DownloadIndexStore(private val file: File) {

    private val json get() = TrackJson.json
    private val writeLock = Mutex() // serialize writes so concurrent saves can't clash on the temp file

    /**
     * Reads the index, keyed by `ProviderRef.identityKey`. Missing or unreadable file → empty.
     *
     * Deliberately **not** `suspend`. `PlaybackService` restores the session and calls `prepare()`
     * within milliseconds of service creation, which fires `resolveDataSpec` immediately. An async load
     * loses that race, and a downloaded track would silently resolve over the network — which fails
     * outright when offline, breaking exactly the promise downloads exist for. So the repository reads
     * this once, synchronously, in its `init`.
     */
    fun load(): Map<String, DownloadedTrack> = runCatching {
        if (!file.exists()) return emptyMap()
        json.decodeFromString(PersistedIndex.serializer(), file.readText())
            .entries
            .map { it.toDownloaded() }
            .associateBy { it.key }
    }.getOrDefault(emptyMap())

    /** Writes [index] atomically. An empty index deletes the file rather than writing `{}`. */
    suspend fun save(index: Map<String, DownloadedTrack>) {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                runCatching {
                    if (index.isEmpty()) {
                        file.delete()
                        return@runCatching
                    }
                    val text = json.encodeToString(
                        PersistedIndex.serializer(),
                        PersistedIndex(index.values.map { PersistedDownload.from(it) }),
                    )
                    val tmp = File(file.parentFile, "${file.name}.tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(file)) {
                        file.writeText(text) // fall back to a direct write if the atomic rename fails
                        tmp.delete()
                    }
                }
            }
        }
    }
}

// ---- On-disk shape (private) ----

@Serializable
private data class PersistedIndex(val entries: List<PersistedDownload>)

/**
 * The key is not stored: it is derived from `track.source.identityKey` on read, so the index physically
 * cannot disagree with the track it points at.
 */
@Serializable
private data class PersistedDownload(
    val track: Track,
    val fileName: String,
    val sizeBytes: Long,
    val container: String,
    val mimeType: String? = null,
    val downloadedAtIso: String,
    val exportedUri: String? = null,
) {
    fun toDownloaded() = DownloadedTrack(
        track = track,
        fileName = fileName,
        sizeBytes = sizeBytes,
        container = container,
        mimeType = mimeType,
        downloadedAtIso = downloadedAtIso,
        exportedUri = exportedUri,
    )

    companion object {
        fun from(d: DownloadedTrack) = PersistedDownload(
            // Strip the ephemeral stream candidates — resolved URLs are never written to disk. The local
            // file this row points at is durable state and is kept in `fileName`, not in the track.
            track = d.track.stripResolutionState(),
            fileName = d.fileName,
            sizeBytes = d.sizeBytes,
            container = d.container,
            mimeType = d.mimeType,
            downloadedAtIso = d.downloadedAtIso,
            exportedUri = d.exportedUri,
        )
    }
}
