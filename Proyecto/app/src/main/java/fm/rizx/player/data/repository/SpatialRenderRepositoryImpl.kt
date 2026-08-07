package fm.rizx.player.data.repository

import fm.rizx.player.core.error.toSafeMessage
import fm.rizx.player.data.download.AudioTagWriter
import fm.rizx.player.data.download.DownloadNotifier
import fm.rizx.player.data.download.MediaStoreExporter
import fm.rizx.player.data.download.Mp3Transcoder
import fm.rizx.player.data.download.NoopDownloadNotifier
import fm.rizx.player.data.download.NotDownloadableException
import fm.rizx.player.data.download.TrackDownloader
import fm.rizx.player.data.download.isDownloadable
import fm.rizx.player.data.genre.TrackGenreResolver
import fm.rizx.player.data.local.store.SpatialAudioProfileStore
import fm.rizx.player.data.local.store.SpatialRenderStore
import fm.rizx.player.domain.model.DownloadFormat
import fm.rizx.player.domain.model.DownloadedTrack
import fm.rizx.player.domain.model.SoundGenre
import fm.rizx.player.domain.model.SpatialAudioProfile
import fm.rizx.player.domain.model.SpatialRender
import fm.rizx.player.domain.model.SpatialRenderState
import fm.rizx.player.domain.model.SpatialRenderStatus
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.stripResolutionState
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.domain.repository.SpatialRenderRepository
import fm.rizx.player.domain.usecase.CandidateResult
import fm.rizx.player.domain.usecase.SmartSpatialProfiles
import fm.rizx.player.domain.usecase.StreamingResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders 8D MP3s.
 *
 * ```
 * best allowed source → fetch → decode → spatializer → LAME 320 stereo → tags → own index → Music/Rizx
 * ```
 *
 * **Its own directory.** [TrackDownloader] is constructed with the folder it owns, and it deletes
 * anything in that folder its caller does not claim — so a render sitting in the downloads folder would
 * be swept away on the next start by an index that has never heard of it. A second downloader, pointed
 * somewhere else, keeps both sweeps correct and costs nothing.
 *
 * **The source is the one an MP3 download would fetch**, which is deliberate rather than incidental: it
 * asks for the best-*sounding* rendition to feed an encoder (normally Opus) and never reaches for a
 * community FLAC. Pulling 25 MB of lossless to re-encode it to 320 kbps would spend someone's data on
 * quality the output cannot keep.
 *
 * **One at a time.** Rendering is decode plus DSP plus a pure-Java LAME, all CPU; running two would not
 * finish the pair any sooner and would compete with whatever is playing.
 */
class SpatialRenderRepositoryImpl(
    private val store: SpatialRenderStore,
    private val downloader: TrackDownloader,
    private val resolver: StreamingResolver,
    private val profiles: SpatialAudioProfileStore,
    private val genres: TrackGenreResolver,
    /** Builds a transcoder whose encoder applies [profile]. Injected so JVM tests can run the pipeline. */
    private val newTranscoder: (profile: SpatialAudioProfile) -> Mp3Transcoder,
    /**
     * Keeps the process alive while a render runs — decode plus DSP plus a pure-Java LAME is the
     * longest job the app has, and without this, walking away from the app loses it.
     */
    private val notifier: DownloadNotifier = NoopDownloadNotifier,
    private val exporter: MediaStoreExporter? = null,
    private val tagWriter: AudioTagWriter? = null,
    private val settings: SettingsRepository? = null,
    private val now: () -> Instant = { Instant.now() },
    io: CoroutineDispatcher = Dispatchers.IO,
) : SpatialRenderRepository {

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val renderLock = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _renders = MutableStateFlow<Map<String, SpatialRender>>(emptyMap())
    override val renders: StateFlow<Map<String, SpatialRender>> = _renders.asStateFlow()

    private val _states = MutableStateFlow<Map<String, SpatialRenderState>>(emptyMap())
    override val states: StateFlow<Map<String, SpatialRenderState>> = _states.asStateFlow()

    init {
        scope.launch {
            _renders.value = store.load()
            reconcile()
        }
    }

    override fun render(track: Track) {
        val key = track.source.identityKey
        if (jobs[key]?.isActive == true) return
        if (_renders.value[key]?.let { downloader.fileFor(it.fileName) != null } == true) return
        // The state goes up before the service is asked for, so the watcher's very first read of the
        // flow already sees this render and does not conclude there is nothing to stay alive for.
        setState(key, SpatialRenderState(SpatialRenderStatus.FETCHING))
        notifier.start()
        jobs[key] = scope.launch {
            try {
                renderLock.withLock { run(track, key) }
            } catch (e: CancellationException) {
                clearState(key)
                throw e
            } catch (e: Throwable) {
                setState(
                    key,
                    SpatialRenderState(SpatialRenderStatus.FAILED, error = e.toSafeMessage("8D render failed")),
                )
            } finally {
                jobs.remove(key)
            }
        }
    }

    override fun cancel(key: String) {
        jobs.remove(key)?.cancel()
        clearState(key)
    }

    override suspend fun delete(key: String) {
        cancel(key)
        val entry = _renders.value[key] ?: return
        downloader.delete(entry.fileName)
        persist(_renders.value - key)
    }

    // ---- Internals ----

    private suspend fun run(track: Track, key: String) {
        val sourceFile = fetchSource(track, key)
        try {
            setState(key, SpatialRenderState(SpatialRenderStatus.RENDERING))
            val profile = profileFor(track, key)
            val rendered = downloader.adopt(key, container = MP3, mimeType = MP3_MIME) { part ->
                newTranscoder(profile).transcode(sourceFile, part)
            }
            // The file says what it is, in its own tags and in the name it exports under — otherwise it
            // lands in the phone's Music folder as a second copy of a song, indistinguishable from the
            // ordinary download until it plays.
            tagWriter?.let { writer -> runCatching { writer.tag(rendered.file, track.marked(), null, null, null) } }
            val entry = SpatialRender(
                track = track.stripResolutionState(),
                fileName = rendered.file.name,
                sizeBytes = rendered.sizeBytes,
                profileLabel = profile.label,
                renderedAtIso = now().toString(),
            )
            persist(_renders.value + (key to entry))
            copyToPhoneIfWanted(key)
            setState(key, SpatialRenderState(SpatialRenderStatus.COMPLETE, progressPercent = 100))
        } finally {
            // The source was scratch — it is a different codec under a different name, and nothing
            // claims it, so leaving it behind would be a silent second copy of every rendered song.
            sourceFile.delete()
        }
    }

    private suspend fun fetchSource(track: Track, key: String): java.io.File {
        val stream = resolveSource(track) ?: throw NotDownloadableException("No playable source found")
        if (!stream.isDownloadable()) throw NotDownloadableException("No downloadable source for this song")
        return downloader.download(SOURCE_KEY_PREFIX + key, stream) { percent ->
            setState(key, SpatialRenderState(SpatialRenderStatus.FETCHING, progressPercent = percent))
        }.file
    }

    /** Exactly what [DownloadFormat.MP3] resolves to: the best-sounding rendition, to feed an encoder. */
    private suspend fun resolveSource(track: Track): fm.rizx.player.domain.model.Stream? {
        val candidates = when (val r = resolver.resolveCandidatesForTrack(track)) {
            is CandidateResult.Success -> r.candidates
            is CandidateResult.Failure -> return null
        }
        for (candidate in candidates.filterNot { it.failed }) {
            resolver.resolveStreamForCandidate(candidate, forDownload = true, downloadFormat = DownloadFormat.MP3)
                .stream?.let { return it }
        }
        return null
    }

    /**
     * The same profile the player would use, from the same cache.
     *
     * A song already listened to with the effect on carries a measurement, so its render is shaped to
     * the recording. One that has not is rendered from its genre alone — there is no way to measure a
     * song without playing it, and making the user listen first would be a strange thing to demand.
     */
    private suspend fun profileFor(track: Track, key: String): SpatialAudioProfile {
        val cached = runCatching { profiles.get(key) }.getOrNull()
        val genre = cached?.genre?.let { name -> SoundGenre.entries.firstOrNull { it.name == name } }
            ?: runCatching { genres.resolve(track).genre }.getOrNull()
            ?: SoundGenre.UNKNOWN
        return SmartSpatialProfiles.profileFor(genre, cached?.analysis)
    }

    /** Marks the title so tags and the exported file name say plainly which version this is. */
    private fun Track.marked(): Track = copy(title = "$title $MARK")

    private suspend fun copyToPhoneIfWanted(key: String) {
        if (settings?.saveDownloadsToPhone?.first() != true) return
        val publisher = exporter ?: return
        val entry = _renders.value[key] ?: return
        val file = downloader.fileFor(entry.fileName) ?: return
        // The exporter names the file from the track it is given, so the marked title is what reaches
        // the Music folder — without it MediaStore would hold two identically named songs.
        val asDownload = DownloadedTrack(
            track = entry.track.marked(),
            fileName = entry.fileName,
            sizeBytes = entry.sizeBytes,
            container = MP3,
            mimeType = MP3_MIME,
            downloadedAtIso = entry.renderedAtIso,
        )
        runCatching { publisher.export(asDownload, file) }
            .getOrNull()
            ?.getOrNull()
            ?.let { persist(_renders.value + (key to entry.copy(exportedUri = it.uri))) }
    }

    /** Drops partials and entries whose file is gone, then sweeps files nothing points at. */
    private suspend fun reconcile() {
        downloader.sweepPartials()
        val alive = _renders.value.filterValues { downloader.fileFor(it.fileName) != null }
        if (alive.size != _renders.value.size) persist(alive)
        downloader.deleteOrphans(alive.values.map { it.fileName }.toSet())
    }

    private suspend fun persist(index: Map<String, SpatialRender>) {
        _renders.value = index
        store.save(index)
    }

    private fun setState(key: String, state: SpatialRenderState) {
        _states.value = _states.value + (key to state)
    }

    private fun clearState(key: String) {
        _states.value = _states.value - key
    }

    private companion object {
        const val MP3 = "mp3"
        const val MP3_MIME = "audio/mpeg"

        /** Keeps the fetched source's file name away from the rendered MP3's, whatever codec it is. */
        const val SOURCE_KEY_PREFIX = "8dsrc#"

        /** What the rendered file calls itself, in its tags and in the phone's Music folder. */
        const val MARK = "(8D)"
    }
}
