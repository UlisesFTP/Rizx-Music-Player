package fm.rizx.player.data.repository

import fm.rizx.player.data.download.DownloadNotifier
import fm.rizx.player.data.download.MediaStoreExporter
import fm.rizx.player.data.download.NotDownloadableException
import fm.rizx.player.data.download.TrackDownloader
import fm.rizx.player.data.download.isDownloadable
import fm.rizx.player.data.local.store.DownloadIndexStore
import fm.rizx.player.domain.model.DownloadState
import fm.rizx.player.domain.model.DownloadStatus
import fm.rizx.player.domain.model.DownloadedTrack
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.stripResolutionState
import fm.rizx.player.domain.repository.DownloadRepository
import fm.rizx.player.domain.usecase.CandidateResult
import fm.rizx.player.domain.usecase.StreamingResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The offline library: an identity-keyed index of tracks with audio on disk, plus the queue that fills it.
 *
 * **This is the authority on local files.** `Track.localFile` is never read or written — the same track
 * exists as separate JSON blobs in playlists, favorites, recents and the session, and `stripResolutionState`
 * deliberately preserves `localFile`, so a path written into those copies would outlive the deleted file
 * forever with nothing to clean it up. One keyed index instead.
 *
 * **Sequential by construction.** Kotlin's [Mutex] is FIFO-fair, so wrapping each fetch in one yields an
 * ordered, one-file-at-a-time queue with no actor, channel or semaphore — and it keeps a 40-track batch
 * from saturating a connection the user may also be streaming over.
 */
class DownloadRepositoryImpl(
    private val store: DownloadIndexStore,
    private val downloader: TrackDownloader,
    private val resolver: StreamingResolver,
    private val exporter: MediaStoreExporter,
    private val notifier: DownloadNotifier,
    private val now: () -> Instant = { Instant.now() },
    /** Injectable so tests drive the queue deterministically instead of hopping to a real IO thread. */
    io: CoroutineDispatcher = Dispatchers.IO,
) : DownloadRepository {

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val fetchLock = Mutex() // FIFO-fair ⇒ this *is* the queue
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _index = MutableStateFlow<Map<String, DownloadedTrack>>(emptyMap())
    private val _transient = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    override val downloads: StateFlow<List<DownloadedTrack>> =
        _index.map { it.values.sortedByDescending(DownloadedTrack::downloadedAtIso) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val states: StateFlow<Map<String, DownloadState>> =
        combine(_index, _transient) { index, transient ->
            index.keys.associateWith { DownloadState(DownloadStatus.COMPLETE) } + transient
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        // Synchronous on purpose. PlaybackService restores the session and calls prepare() within
        // milliseconds of service creation, which fires resolveDataSpec immediately; an async load would
        // lose that race and a downloaded track would resolve over the network — which fails outright
        // offline, breaking the one thing downloads exist for.
        _index.value = store.load()
        scope.launch { reconcile() }
    }

    // ---- Reads ----

    /**
     * Synchronous and non-blocking: called from an ExoPlayer loader thread, including outside
     * `QueueStreamResolver`'s `runBlocking`. A map lookup plus one `exists()` stat.
     *
     * Returning null *is* the fallback — a vanished file just means the resolver does its normal network
     * resolve, with nothing for the user to see.
     */
    override fun localStream(track: Track): Stream? {
        val entry = _index.value[track.source.identityKey] ?: return null
        val file = downloader.fileFor(entry.fileName) ?: return null
        return Stream(
            url = file.toURI().toString(),
            protocol = StreamProtocol.FILE,
            mimeType = entry.mimeType,
            container = entry.container,
            qualityLabel = "Downloaded",
            durationMs = track.durationMs,
            contentLengthBytes = entry.sizeBytes,
            source = track.source,
        )
    }

    // ---- Writes ----

    override fun download(track: Track) {
        val key = track.source.identityKey
        if (_index.value.containsKey(key) || jobs.containsKey(key)) return
        setTransient(key, DownloadState(DownloadStatus.QUEUED))
        notifier.start()
        jobs[key] = scope.launch {
            try {
                fetchLock.withLock { fetch(track, key) }
            } catch (e: CancellationException) {
                clearTransient(key)
                throw e
            } catch (e: Exception) {
                // A failed download must never take the app or the rest of the batch down with it.
                setTransient(key, DownloadState(DownloadStatus.FAILED, error = e.message ?: "Download failed"))
            } finally {
                jobs.remove(key)
                if (jobs.isEmpty()) notifier.stop()
            }
        }
    }

    override fun downloadAll(tracks: List<Track>) = tracks.forEach(::download)

    override fun cancel(key: String) {
        jobs.remove(key)?.cancel()
        clearTransient(key)
        if (jobs.isEmpty()) notifier.stop()
    }

    override suspend fun delete(key: String) {
        val entry = _index.value[key] ?: return
        downloader.delete(entry.fileName)
        persist(_index.value - key)
        clearTransient(key)
    }

    override suspend fun deleteAll() {
        _index.value.values.forEach { downloader.delete(it.fileName) }
        persist(emptyMap())
        _transient.value = emptyMap()
    }

    override suspend fun markCorrupt(key: String) {
        delete(key)
        setTransient(key, DownloadState(DownloadStatus.FAILED, error = "File unreadable — streaming instead"))
    }

    override suspend fun export(key: String): Result<String> {
        val entry = _index.value[key] ?: return Result.failure(IllegalStateException("Not downloaded"))
        val file = downloader.fileFor(entry.fileName)
            ?: return Result.failure(IllegalStateException("File is missing"))
        return exporter.export(entry, file)
            .onSuccess { persist(_index.value + (key to entry.copy(exportedUri = it.uri))) }
            .map { it.displayName }
    }

    // ---- Internals ----

    private suspend fun fetch(track: Track, key: String) {
        setTransient(key, DownloadState(DownloadStatus.DOWNLOADING))
        val stream = resolveStream(track) ?: throw NotDownloadableException("No playable source found")
        if (!stream.isDownloadable()) {
            throw NotDownloadableException("No downloadable source for this song")
        }
        val done = downloader.download(key, stream) { percent ->
            setTransient(key, DownloadState(DownloadStatus.DOWNLOADING, progressPercent = percent))
        }
        val entry = DownloadedTrack(
            // Keep only the durable half: the resolved URL that fetched these bytes is ephemeral.
            track = track.stripResolutionState(),
            fileName = done.file.name,
            sizeBytes = done.sizeBytes,
            container = done.container,
            mimeType = done.mimeType,
            downloadedAtIso = now().toString(),
        )
        // Index only after the file is validated and renamed — an entry pointing at a partial file would
        // be handed to ExoPlayer forever.
        persist(_index.value + (key to entry))
        clearTransient(key)
    }

    private suspend fun resolveStream(track: Track): Stream? {
        val candidates = when (val r = resolver.resolveCandidatesForTrack(track)) {
            is CandidateResult.Success -> r.candidates
            is CandidateResult.Failure -> return null
        }
        for (candidate in candidates.filterNot { it.failed }) {
            resolver.resolveStreamForCandidate(candidate).stream?.let { return it }
        }
        return null
    }

    /**
     * Startup hygiene: drop `.part` files, drop index entries whose file is gone (cleared app data, an
     * external delete, a process death mid-download), and sweep files nothing points at.
     */
    private suspend fun reconcile() {
        downloader.sweepPartials()
        val alive = _index.value.filterValues { downloader.fileFor(it.fileName) != null }
        if (alive.size != _index.value.size) persist(alive)
        downloader.deleteOrphans(alive.values.map { it.fileName }.toSet())
    }

    private suspend fun persist(index: Map<String, DownloadedTrack>) {
        _index.value = index
        store.save(index)
    }

    private fun setTransient(key: String, state: DownloadState) {
        _transient.value = _transient.value + (key to state)
    }

    private fun clearTransient(key: String) {
        _transient.value = _transient.value - key
    }
}
