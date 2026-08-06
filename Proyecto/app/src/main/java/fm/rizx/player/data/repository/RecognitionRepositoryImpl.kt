package fm.rizx.player.data.repository

import fm.rizx.player.data.local.db.RecognitionHistoryDao
import fm.rizx.player.data.local.db.RecognitionHistoryEntity
import fm.rizx.player.data.local.store.TrackJson
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.recognition.MicrophoneRecorder
import fm.rizx.player.domain.recognition.RecognitionError
import fm.rizx.player.domain.recognition.RecognitionHistoryItem
import fm.rizx.player.domain.recognition.RecognitionMatch
import fm.rizx.player.domain.recognition.RecognitionOutcome
import fm.rizx.player.domain.recognition.RecognitionProvider
import fm.rizx.player.domain.recognition.RecognitionRepository
import fm.rizx.player.domain.recognition.RecognitionState
import fm.rizx.player.domain.recognition.RecognitionTrackResolver
import fm.rizx.player.domain.recognition.RecordingFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The one recognition session that may be in flight, and the log of past ones.
 *
 * **It is a singleton on purpose, and outlives every screen.** A recognition takes ten seconds of
 * listening plus a network round trip, and during that time a phone can rotate, the user can wander
 * into Settings to grant the microphone permission, or navigate away and back. If the session lived in
 * a ViewModel, each of those would silently abandon a capture that was already half-done. Here they
 * all rejoin the same session.
 *
 * **Late results can never win.** Every session takes a generation number, and a session only writes
 * state while it is still the current one. Without that, a slow request from a cancelled attempt
 * arrives after the user has started a new one and confidently overwrites it with the previous song —
 * which is both wrong and completely invisible.
 */
class RecognitionRepositoryImpl(
    private val recorder: MicrophoneRecorder,
    private val provider: RecognitionProvider,
    private val resolver: RecognitionTrackResolver,
    private val historyDao: RecognitionHistoryDao,
    io: CoroutineDispatcher,
    private val captureDurationMs: Long = CAPTURE_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val nowIso: () -> String = { Instant.now().toString() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : RecognitionRepository {

    /**
     * A `SupervisorJob` so one failed session cannot poison the scope for the next, and the app's own
     * dispatcher rather than `GlobalScope` so the work is still owned by something.
     */
    private val scope = CoroutineScope(SupervisorJob() + io)

    private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    override val state: StateFlow<RecognitionState> = _state.asStateFlow()

    private val generation = AtomicLong(0)
    private var session: Job? = null

    override fun start() {
        // Pressing an already-running button means "I meant it", not "start a second microphone".
        if (session?.isActive == true) return
        val current = generation.incrementAndGet()
        session = scope.launch { listen(current) }
    }

    override fun cancel() {
        // Bumped *before* the job is cancelled, so anything the dying session still tries to publish is
        // already stale and cannot repaint the screen it just left.
        generation.incrementAndGet()
        session?.cancel()
        session = null
        _state.value = RecognitionState.Idle
    }

    /** Identical to [cancel] by construction: discarding a result and abandoning a capture both end at Idle. */
    override fun reset() = cancel()

    override fun history(): Flow<List<RecognitionHistoryItem>> =
        historyDao.observe(HISTORY_LIMIT).map { rows -> rows.map { it.toItem() } }

    override suspend fun deleteFromHistory(id: String) = historyDao.delete(id)

    override suspend fun clearHistory() = historyDao.clear()

    private suspend fun listen(generation: Long) {
        try {
            if (!recorder.isAvailable()) {
                publish(generation, RecognitionState.Failed(RecognitionError.MICROPHONE_UNAVAILABLE, retryable = false))
                return
            }

            publish(generation, RecognitionState.Listening(elapsedMs = 0, amplitude = 0f))
            val startedAt = now()
            val audio = withTimeout(captureDurationMs + CAPTURE_SLACK_MS) {
                recorder.record(captureDurationMs) { amplitude ->
                    publish(generation, RecognitionState.Listening(now() - startedAt, amplitude))
                }
            }

            publish(generation, RecognitionState.Processing)
            when (val outcome = withTimeout(LOOKUP_TIMEOUT_MS) { provider.recognize(audio) }) {
                is RecognitionOutcome.Matched -> {
                    // Resolution is a convenience, not the result. A catalogue that is down must not
                    // turn a successful identification into a failure.
                    val track = runCatching { resolver.resolve(outcome.match) }.getOrNull()
                    remember(outcome.match, track)
                    publish(generation, RecognitionState.Matched(outcome.match, track))
                }
                RecognitionOutcome.NoMatch -> publish(generation, RecognitionState.NoMatch)
                is RecognitionOutcome.Failed ->
                    publish(generation, RecognitionState.Failed(outcome.error, outcome.error.isWorthRetrying()))
            }
        } catch (e: TimeoutCancellationException) {
            // Caught before CancellationException below, which it extends — otherwise a service that
            // never answers is indistinguishable from a user who pressed cancel.
            publish(generation, RecognitionState.Failed(RecognitionError.NETWORK, retryable = true))
        } catch (e: RecordingFailure) {
            publish(generation, RecognitionState.Failed(e.error, e.error.isWorthRetrying()))
        } catch (e: CancellationException) {
            if (generation == this.generation.get()) _state.value = RecognitionState.Idle
            throw e
        } catch (e: Exception) {
            publish(generation, RecognitionState.Failed(RecognitionError.UNKNOWN, retryable = true))
        }
    }

    /** A session may only speak while it is still the current one. */
    private fun publish(generation: Long, next: RecognitionState) {
        if (generation == this.generation.get()) _state.value = next
    }

    /**
     * Writes the identification down — and nothing else. No audio, no fingerprint, no response body;
     * the resolved track goes through the same encoder as playlists and favorites, which strips
     * resolution state so no ephemeral stream URL is ever persisted.
     *
     * Failing to record history must not cost the user their result, so this swallows.
     */
    private suspend fun remember(match: RecognitionMatch, track: Track?) {
        runCatching {
            historyDao.insert(
                RecognitionHistoryEntity(
                    id = newId(),
                    provider = match.provider,
                    providerTrackId = match.providerTrackId,
                    title = match.title,
                    artist = match.artist,
                    album = match.album,
                    isrc = match.isrc,
                    artworkUrl = match.artworkHqUrl ?: match.artworkUrl,
                    genre = match.genre,
                    releaseDate = match.releaseDate,
                    label = match.label,
                    externalUrl = match.externalUrl,
                    appleTrackId = match.appleTrackId,
                    resolvedProvider = track?.source?.provider,
                    resolvedSourceId = track?.source?.id,
                    resolvedTrackJson = track?.let { TrackJson.encodeTrack(it) },
                    recognizedAtIso = nowIso(),
                ),
            )
            historyDao.prune(HISTORY_LIMIT)
        }
    }

    private fun RecognitionHistoryEntity.toItem() = RecognitionHistoryItem(
        id = id,
        match = RecognitionMatch(
            provider = provider,
            providerTrackId = providerTrackId,
            title = title,
            artist = artist,
            album = album,
            isrc = isrc,
            artworkUrl = artworkUrl,
            artworkHqUrl = artworkUrl,
            genre = genre,
            releaseDate = releaseDate,
            label = label,
            externalUrl = externalUrl,
            appleTrackId = appleTrackId,
        ),
        resolvedTrack = resolvedTrackJson?.let { runCatching { TrackJson.decodeTrack(it) }.getOrNull() },
        recognizedAtIso = recognizedAtIso,
    )

    /** Whether offering "try again" would be honest. Two failures need a decision, not another attempt. */
    private fun RecognitionError.isWorthRetrying(): Boolean = when (this) {
        RecognitionError.PERMISSION, RecognitionError.MICROPHONE_UNAVAILABLE -> false
        else -> true
    }

    private companion object {
        const val CAPTURE_MS = 10_000L

        /** Opening the microphone is not instant; the timeout must not fire on a healthy capture. */
        const val CAPTURE_SLACK_MS = 8_000L

        /** Bounds the provider's own retries and backoff so a stuck service cannot listen forever. */
        const val LOOKUP_TIMEOUT_MS = 45_000L

        const val HISTORY_LIMIT = 200
    }
}
