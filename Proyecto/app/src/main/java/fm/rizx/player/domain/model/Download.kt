package fm.rizx.player.domain.model

/** Where a track is in the download pipeline. Only [COMPLETE] survives a restart — the rest are in-flight. */
enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETE, FAILED }

/**
 * A track whose audio is on disk, ready to play with no network.
 *
 * Identity is [Track.source] (`ProviderRef`), exactly like the resolver's stream cache — so a download
 * made from a playlist is found again from the Liked tab, the queue, or anywhere else the same track
 * appears as a separate stored copy. Never keyed by `QueueItem.id`, which is re-minted per insertion.
 *
 * [fileName] is **relative** to the downloads directory, not an absolute URI: `getExternalFilesDir`
 * differs between the release and `.debug` application ids and can move between storage volumes, so the
 * path is rebuilt at read time.
 */
data class DownloadedTrack(
    val track: Track,
    val fileName: String,
    val sizeBytes: Long,
    /** The real container the bytes are in (`m4a`/`webm`/`mp3`), taken from the resolved stream. */
    val container: String,
    val mimeType: String? = null,
    val downloadedAtIso: String,
    /** The `content://` uri this was exported to under `Music/Rizx`, or null if never exported. */
    val exportedUri: String? = null,
) {
    val key: String get() = track.source.identityKey
}

/** What a download button should render for one track. [progressPercent] only means anything while DOWNLOADING. */
data class DownloadState(
    val status: DownloadStatus,
    val progressPercent: Int = 0,
    val error: String? = null,
)
