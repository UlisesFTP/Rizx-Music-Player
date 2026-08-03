package fm.rizx.player.data.repository

import fm.rizx.player.core.error.toSafeMessage
import fm.rizx.player.data.download.AudioTagWriter
import fm.rizx.player.data.download.DownloadNotifier
import fm.rizx.player.data.download.DownloadedFile
import fm.rizx.player.data.download.MediaStoreExporter
import fm.rizx.player.data.download.Mp3Transcoder
import fm.rizx.player.data.download.NotDownloadableException
import fm.rizx.player.data.download.OpusRemuxer
import fm.rizx.player.data.download.TrackDownloader
import fm.rizx.player.core.network.DataSaverState
import fm.rizx.player.data.download.exportFileName
import fm.rizx.player.data.download.isDownloadable
import fm.rizx.player.domain.repository.CachedAudioReader
import fm.rizx.player.data.lossless.toStream
import fm.rizx.player.domain.lossless.LosslessResolver
import fm.rizx.player.data.local.store.DownloadIndexStore
import fm.rizx.player.domain.model.DownloadFormat
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
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.domain.usecase.CandidateResult
import fm.rizx.player.domain.usecase.StreamingResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
 * **Two at a time.** The queue used to be a FIFO [Mutex] — strictly one file at a time — which made a
 * 40-track batch a serial hour. A [Semaphore] of [MAX_PARALLEL_TRACKS] halves that wall-clock while the
 * per-file worker cap in [TrackDownloader] keeps the socket count civil for whatever the user is
 * actively streaming.
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
    /** The optional community-FLAC source. Null in tests that don't exercise it, and whenever it is off. */
    private val lossless: LosslessResolver? = null,
    /** Read for the download-format preference. Null in tests that pin the format per call. */
    private val settings: SettingsRepository? = null,
    /** Holds a download back while saving data on a metered link. Null in tests that don't exercise it. */
    private val dataSaver: DataSaverState? = null,
    /** Re-encodes to MP3 for [DownloadFormat.MP3]. Null in tests that don't exercise conversion. */
    private val transcoder: Mp3Transcoder? = null,
    /** Rewraps WebM/Opus as Ogg Opus so it can be tagged. Null in tests that don't exercise it. */
    private val opusRemuxer: OpusRemuxer? = null,
    /** The streaming byte-cache, for downloads of songs already heard. Null degrades to the network. */
    private val cachedAudio: CachedAudioReader? = null,
) : DownloadRepository {

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val fetchSlots = Semaphore(MAX_PARALLEL_TRACKS)

    /** One publish at a time — see [export]. */
    private val exportLock = Mutex()
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
            // What the readout under the player says a downloaded song is. Measured, not guessed: the
            // codec is the container's own, and the bitrate is this file's real bytes over its real
            // duration — the same arithmetic the lossless mapper does. Without them the line simply went
            // blank for every download, which is worse than saying the little that is actually known.
            codec = codecOf(entry.container),
            bitrateKbps = bitrateOf(entry.sizeBytes, track.durationMs),
            container = entry.container,
            qualityLabel = "Downloaded",
            durationMs = track.durationMs,
            contentLengthBytes = entry.sizeBytes,
            source = track.source,
        )
    }

    /** The codec a downloaded container holds. Null when we would only be guessing. */
    private fun codecOf(container: String): String? = when (container.lowercase()) {
        "m4a", "mp4", "aac" -> "AAC"
        "mp3" -> "MP3"
        "opus", "ogg", "oga", "webm" -> "OPUS"
        "flac" -> "FLAC"
        else -> null
    }

    private fun bitrateOf(sizeBytes: Long, durationMs: Long?): Int? {
        if (durationMs == null || durationMs <= 0 || sizeBytes <= 0) return null
        return (sizeBytes * 8 / durationMs).toInt().takeIf { it > 0 }
    }

    // ---- Writes ----

    override fun download(track: Track, format: DownloadFormat?) {
        val key = track.source.identityKey
        if (_index.value.containsKey(key) || jobs.containsKey(key)) return
        setTransient(key, DownloadState(DownloadStatus.QUEUED))
        notifier.start()
        jobs[key] = scope.launch {
            try {
                fetchSlots.withPermit { fetch(track, key, format ?: configuredFormat()) }
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

    /**
     * Publishes a download into `Music/Rizx`, or reports why it couldn't.
     *
     * Serialized: MediaStore answers a repeat insert of the same name with `Artist - Title (1).opus`
     * rather than refusing it, so two callers arriving together — the automatic copy and an impatient
     * tap on the row's button — would leave two files behind. One at a time, and the second one finds
     * the [DownloadedTrack.exportedUri] the first wrote.
     */
    override suspend fun export(key: String): Result<String> = exportLock.withLock {
        val entry = _index.value[key] ?: return@withLock Result.failure(IllegalStateException("Not downloaded"))
        // Already on the phone and still there? Then this is a no-op rather than a second copy.
        entry.exportedUri?.let { uri ->
            if (exporter.exists(uri)) return@withLock Result.success(entry.exportName())
        }
        val file = downloader.fileFor(entry.fileName)
            ?: return@withLock Result.failure(IllegalStateException("File is missing"))
        exporter.export(entry, file)
            .onSuccess { persist(_index.value + (key to entry.copy(exportedUri = it.uri))) }
            .map { it.displayName }
    }

    // ---- Internals ----

    /** The name this download wears in the phone's Music folder — the exporter's own naming, reused. */
    private fun DownloadedTrack.exportName(): String = exportFileName(
        artist = track.artists.joinToString { it.name },
        title = track.title,
        extension = container,
        fallback = fileName.substringBeforeLast('.'),
    )

    /**
     * Copies a finished download into the phone's Music folder when the user asked for that.
     *
     * Runs after the entry is indexed, and its failure is swallowed on purpose: the bytes are already a
     * complete, playable, offline download, and "the copy didn't make it" is something the row's own
     * button can fix — it must never turn a good download into a failed one.
     */
    private suspend fun copyToPhoneIfWanted(key: String) {
        if (settings?.saveDownloadsToPhone?.first() != true) return
        runCatching { export(key) }
    }

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

    /** The Settings default, or [DownloadFormat.ORIGINAL] when this instance was built without settings. */
    private suspend fun configuredFormat(): DownloadFormat =
        settings?.downloadFormat?.first() ?: DownloadFormat.ORIGINAL

    private suspend fun fetch(track: Track, key: String, format: DownloadFormat) {
        // The one thing in the app that pulls a whole song's worth of bytes on purpose — 5 MB for an M4A,
        // 25 MB for a FLAC — and until now the only one that never asked what the connection costs.
        //
        // Metered *and* saving, both: blocking a download on Wi-Fi would be backwards, since downloading
        // on Wi-Fi is precisely how someone avoids spending mobile data later.
        if (dataSaver?.blocksBulkTransfer() == true) {
            throw NotDownloadableException("Waiting for Wi-Fi — data saver is on")
        }
        setTransient(key, DownloadState(DownloadStatus.DOWNLOADING))
        val done = fromByteCache(track, key, format)
            ?: fromNetwork(track, key, format)
        val finished = repackageOpusIfWanted(convertIfWanted(done, key, format), key, format)
        // Embed the metadata *into* the file so the song carries its cover, artist, album and date into any
        // other player. Best-effort by design: the bytes on disk are already a valid, playable download, so
        // a tagging failure must not fail it.
        tagWriter?.let { writer ->
            val info = runCatching { albumInfoFor(track) }.getOrNull()
            runCatching { writer.tag(finished.file, track, info?.title, info?.releaseDateIso, info?.year) }
        }
        val entry = DownloadedTrack(
            // Keep only the durable half: the resolved URL that fetched these bytes is ephemeral.
            track = track.stripResolutionState(),
            fileName = finished.file.name,
            sizeBytes = finished.sizeBytes,
            container = finished.container,
            mimeType = finished.mimeType,
            downloadedAtIso = now().toString(),
        )
        // Index only after the file is validated and renamed — an entry pointing at a partial file would
        // be handed to ExoPlayer forever.
        persist(_index.value + (key to entry))
        copyToPhoneIfWanted(key)
        clearTransient(key)
    }

    private suspend fun fromNetwork(track: Track, key: String, format: DownloadFormat): DownloadedFile {
        val resolved = resolveForDownload(track, format) ?: throw NotDownloadableException("No playable source found")
        if (!resolved.stream.isDownloadable()) {
            throw NotDownloadableException("No downloadable source for this song")
        }
        return downloader.download(key, resolved.stream, resolved.sha256) { percent ->
            setTransient(key, DownloadState(DownloadStatus.DOWNLOADING, progressPercent = percent))
        }
    }

    /**
     * A song the user has already listened to is already on disk, byte for byte, in the streaming cache —
     * so "download" for it can be a local copy: zero network, near-instant. Only buckets whose codec is
     * what the chosen format would have fetched anyway are adopted:
     *
     * - [DownloadFormat.ORIGINAL] takes only the **taggable** containers it has always produced (m4a,
     *   mp3, flac) — never a cached Opus, which would silently change what "Original" saves.
     * - [DownloadFormat.OPUS] takes the Opus/WebM buckets.
     * - [DownloadFormat.MP3] takes anything whole as *input* for the encoder (the container doesn't
     *   matter; the extractor sniffs it) — the conversion still runs, only the network fetch is skipped.
     * - [DownloadFormat.FLAC] never adopts: its resolver owns verification (magic + optional sha), and a
     *   cached copy exists only when lossless streaming already fetched it — rare enough not to special-case.
     *
     * Best-effort throughout: a `false` copy (eviction raced us) just falls through to the network.
     */
    private suspend fun fromByteCache(track: Track, key: String, format: DownloadFormat): DownloadedFile? {
        val reader = cachedAudio ?: return null
        if (format == DownloadFormat.FLAC) return null
        val cached = runCatching { reader.fullyCachedCodecs(key) }.getOrDefault(emptyList())
        if (cached.isEmpty()) return null

        val (codec, container) = when (format) {
            DownloadFormat.ORIGINAL -> cached.firstOrNull { it.containerForTaggable() != null }
                ?.let { it to it.containerForTaggable()!! }
            DownloadFormat.OPUS -> cached.firstOrNull { it.isOpusFamily() }?.let { it to "webm" }
            DownloadFormat.MP3 -> cached.firstOrNull()?.let { it to it.containerForAnything() }
            DownloadFormat.FLAC -> null
        } ?: return null
        return runCatching {
            downloader.adopt(key, container, mimeType = null) { part ->
                val ok = part.outputStream().use { out -> reader.copyTo(key, codec, out) }
                if (!ok) throw NotDownloadableException("cache copy incomplete")
            }
        }.getOrNull()
    }

    /** The taggable container this cache bucket maps to, or null when adopting it would change ORIGINAL. */
    private fun String.containerForTaggable(): String? = when {
        contains("m4a") || contains("aac") || contains("mp4") -> "m4a"
        contains("mp3") -> "mp3"
        contains("flac") -> "flac"
        else -> null
    }

    private fun String.isOpusFamily(): Boolean = contains("opus") || contains("webm")

    /** Any bucket serves as MP3 input; this only names the temp file the extractor will sniff anyway. */
    private fun String.containerForAnything(): String = containerForTaggable() ?: "webm"

    /**
     * The MP3 format's second act: decode the fetched file, encode it as LAME 320 CBR, and adopt the
     * result through the same validate-and-rename pipeline as any download. A source that is already
     * MP3 (Audius) is kept as-is — a lossy→lossy re-encode with nothing to gain is pure loss.
     */
    private suspend fun convertIfWanted(done: DownloadedFile, key: String, format: DownloadFormat): DownloadedFile {
        if (format != DownloadFormat.MP3 || done.container.equals("mp3", ignoreCase = true)) return done
        val converter = transcoder
            ?: throw NotDownloadableException("MP3 conversion is unavailable")
        setTransient(key, DownloadState(DownloadStatus.CONVERTING))
        return try {
            downloader.adopt(key, container = "mp3", mimeType = "audio/mpeg") { part ->
                converter.transcode(done.file, part)
            }
        } finally {
            // The source file was the *download*; the adopted MP3 replaces it under a different name, so
            // the original must not survive as an orphan for the startup sweep to find.
            if (!done.file.name.endsWith(".mp3")) done.file.delete()
        }
    }

    /**
     * The Opus format's second act: YouTube ships Opus inside **WebM**, and nothing that runs on Android
     * can write tags into that container — which is why an Opus download used to be the one format that
     * arrived with no cover and no artist. Moving the packets into **Ogg** costs no quality (they are
     * copied, not re-encoded), gives the file the `.opus` extension other players expect, and puts it in
     * a container [AudioTagWriter] can write, which is the point.
     *
     * Best-effort, like the tagging it enables: a framework that refuses this file leaves the WebM
     * exactly as downloaded — playable, just untagged, which is what every earlier version produced.
     */
    private suspend fun repackageOpusIfWanted(
        done: DownloadedFile,
        key: String,
        format: DownloadFormat,
    ): DownloadedFile {
        if (format != DownloadFormat.OPUS || done.container.lowercase() in OGG_CONTAINERS) return done
        val remuxer = opusRemuxer ?: return done
        return try {
            downloader.adopt(key, container = "opus", mimeType = "audio/ogg") { part ->
                remuxer.remux(done.file, part)
            }.also {
                // The WebM was the *download*; the Ogg replaces it under a different name, so it must not
                // survive as an orphan for the startup sweep to find.
                done.file.delete()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            done
        }
    }

    /** A stream to save, plus the digest to check it against when the source published one. */
    private data class ResolvedForDownload(val stream: Stream, val sha256: String? = null)

    private suspend fun resolveForDownload(track: Track, format: DownloadFormat): ResolvedForDownload? {
        if (format == DownloadFormat.FLAC) {
            losslessDownloadStream(track)?.let { return it }
            // No verified FLAC for this song: fall back to exactly what ORIGINAL would have saved.
        }
        val candidates = when (val r = resolver.resolveCandidatesForTrack(track)) {
            is CandidateResult.Success -> r.candidates
            is CandidateResult.Failure -> return null
        }
        for (candidate in candidates.filterNot { it.failed }) {
            // forDownload: ORIGINAL asks for a container the tag writer can write into (an untagged file
            // is worse than a better codec); OPUS/MP3 ask for the best-sounding rendition instead — one
            // to keep, the other to feed the encoder. See StreamingProvider.getDownloadStreamUrl.
            resolver.resolveStreamForCandidate(candidate, forDownload = true, downloadFormat = format).stream
                ?.let { return ResolvedForDownload(it) }
        }
        return null
    }

    /**
     * A verified FLAC to save instead of the compressed stream, when the format asks for one.
     *
     * A separate choice from *listening* lossless on purpose: streaming it costs bandwidth once, while
     * downloading it costs 25-27 MB of storage per song permanently, and those are different decisions.
     *
     * Nothing is converted in either direction — the bytes that arrive are the bytes that are written.
     * Re-encoding a FLAC to M4A would throw away exactly what was fetched for, and re-encoding an AAC
     * *to* FLAC would produce a large file that is not lossless in any sense that matters.
     */
    private suspend fun losslessDownloadStream(track: Track): ResolvedForDownload? {
        val resolverForFlac = lossless ?: return null
        val validated = try {
            resolverForFlac.resolve(track)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        return ResolvedForDownload(
            stream = validated.toStream(track.source.identityKey),
            sha256 = validated.candidate.item.sha256,
        )
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

    private companion object {
        /**
         * Two, not more: each file already opens up to three range sockets, and the person this queue
         * belongs to is often streaming a song over the same link at the same time.
         */
        const val MAX_PARALLEL_TRACKS = 2

        /** Containers that already hold Opus the way the rest of the world expects it. */
        val OGG_CONTAINERS = setOf("opus", "ogg", "oga")
    }
}