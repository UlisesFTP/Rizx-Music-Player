package fm.rizx.player.data.repository

import fm.rizx.player.core.error.toSafeMessage
import fm.rizx.player.data.download.AudioTagWriter
import fm.rizx.player.data.download.DownloadNotifier
import fm.rizx.player.data.download.MediaStoreExporter
import fm.rizx.player.data.download.NotDownloadableException
import fm.rizx.player.data.download.TrackDownloader
import fm.rizx.player.data.download.isDownloadable
import fm.rizx.player.data.local.store.DownloadIndexStore
import fm.rizx.player.domain.model.DownloadState
import fm.rizx.player.domain.model.DownloadStatus
import fm.rizx.player.domain.model.DownloadedTrack
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.stripResolutionState
import fm.rizx.player.domain.model.DetailCapability
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
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
    /** Embeds cover/artist/album/date into the finished file. Null in tests that don't exercise tagging. */
    private val tagWriter: AudioTagWriter? = null,
    /** Supplies album detail for the release date. Null when no metadata provider is available. */
    private val registry: ProviderRegistry? = null,
    private val now: () -> Instant = { Instant.now() },
    /** Injectable so tests drive the queue deterministically instead of hopping to a real IO thread. */
    io: CoroutineDispatcher = Dispatchers.IO,
) : DownloadRepository {

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val fetchLock = Mutex() // FIFO-fair ⇒ this *is* the queue
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Album identityKey → its tagging info; one lookup per album, not per track. */
    private val albumInfo = ConcurrentHashMap<String, AlbumInfo>()

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
                setTransient(key, DownloadState(DownloadStatus.FAILED, error = e.toSafeMessage("Download failed")))
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

    /** Album title + release date for tagging. */
    private data class AlbumInfo(val title: String?, val releaseDateIso: String?, val year: Int?)

    /**
     * Album name and release date for [track], or null when unknown.
     *
     * A `Track` only carries a light `AlbumRef`, so the date always needs the provider's album detail. And
     * some sources carry no album at all — a Spotify import's rows give just title/artists/duration — so
     * when the ref is missing the album is recovered by matching artist+title on the metadata provider,
     * the same way cover art is. Memoised per album: downloading a whole album must not repeat the lookup
     * for every track.
     */
    private suspend fun albumInfoFor(track: Track): AlbumInfo? {
        val provider = registry?.list(ProviderKind.METADATA)
            ?.filterIsInstance<MetadataProvider>()
            ?.firstOrNull { DetailCapability.ALBUM_DETAIL in it.detailCapabilities }
            ?: return null

        var albumSource = track.album?.source
        var title = track.album?.title
        if (albumSource == null) {
            val query = listOfNotNull(track.artists.firstOrNull()?.name, track.title)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { return null }
            val match = runCatching {
                provider.search(SearchParams(query = query, limit = 1)).tracks.firstOrNull()
            }.getOrNull()
            albumSource = match?.album?.source
            title = match?.album?.title ?: title
        }

        val key = albumSource?.identityKey ?: return title?.let { AlbumInfo(it, null, null) }
        albumInfo[key]?.let { return it }
        val album = runCatching { provider.albumDetail(albumSource) }.getOrNull()
        val info = AlbumInfo(album?.title ?: title, album?.releaseDateIso, album?.year)
        albumInfo[key] = info
        return info
    }

    private suspend fun fetch(track: Track, key: String) {
        setTransient(key, DownloadState(DownloadStatus.DOWNLOADING))
        val stream = resolveStream(track) ?: throw NotDownloadableException("No playable source found")
        if (!stream.isDownloadable()) {
            throw NotDownloadableException("No downloadable source for this song")
        }
        val done = downloader.download(key, stream) { percent ->
            setTransient(key, DownloadState(DownloadStatus.DOWNLOADING, progressPercent = percent))
        }
        // Embed the metadata *into* the file so the song carries its cover, artist, album and date into any
        // other player. Best-effort by design: the bytes on disk are already a valid, playable download, so
        // a tagging failure must not fail it.
        tagWriter?.let { writer ->
            val info = runCatching { albumInfoFor(track) }.getOrNull()
            runCatching { writer.tag(done.file, track, info?.title, info?.releaseDateIso, info?.year) }
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
            // forDownload: ask for a container the tag writer can actually write into (see
            // StreamingProvider.getDownloadStreamUrl) — an untagged file is worse than a better codec.
            resolver.resolveStreamForCandidate(candidate, forDownload = true).stream?.let { return it }
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