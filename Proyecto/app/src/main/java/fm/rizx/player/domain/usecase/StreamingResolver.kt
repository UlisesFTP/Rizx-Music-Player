package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.PlaybackResolverSettings
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.StreamingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import javax.inject.Inject

/** Outcome of phase-1 candidate discovery — a discriminated union (NUCLEAR_UPSTREAM_STUDY.md §5.1). */
sealed interface CandidateResult {
    data class Success(val candidates: List<StreamCandidate>) : CandidateResult
    data class Failure(val error: String) : CandidateResult
}

/**
 * Two-phase, just-in-time stream resolution (NUCLEAR_UPSTREAM_STUDY.md §5). Pure orchestration over
 * a [StreamingRepository]; holds no state and touches no Android/Media3 APIs. The reactive driver
 * (observe the queue, dedup by resolution key, cancel the previous [kotlinx.coroutines.Job]) and the
 * `MediaItem`/`MediaSource` mapping are Phase 8 — this class is the testable core they build on.
 *
 * Resolved `Stream.url`s are **ephemeral**: only [StreamCandidate.lastResolvedAtIso] and
 * [StreamCandidate.failed] are durable; the URL is re-resolved before playback and never persisted.
 *
 * @param nowEpochMs injectable clock (epoch millis) driving expiry checks and timestamp stamping.
 */
class StreamingResolver @Inject constructor(
    private val streaming: StreamingRepository,
    private val settings: PlaybackResolverSettings = PlaybackResolverSettings(),
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Phase 1 — candidate discovery. Reuses [Track.streamCandidates] when the list is present and not
     * entirely stale; otherwise searches the active provider. Provider errors become [Failure] so the
     * caller can surface an error state instead of crashing.
     */
    suspend fun resolveCandidatesForTrack(track: Track): CandidateResult {
        val existing = track.streamCandidates
        if (existing.isNotEmpty() && existing.any { it.isUsable(nowEpochMs()) }) {
            return CandidateResult.Success(existing)
        }
        return try {
            CandidateResult.Success(streaming.searchForTrack(track))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CandidateResult.Failure(e.message ?: "Stream candidate search failed")
        }
    }

    /**
     * Phase 2 — just-in-time URL resolution. Returns the candidate unchanged when it is [failed] or
     * already carries a non-expired [stream]; otherwise resolves a fresh URL with retry/backoff,
     * stamping [lastResolvedAtIso] on success or flagging [failed] once attempts are exhausted.
     */
    suspend fun resolveStreamForCandidate(candidate: StreamCandidate): StreamCandidate {
        if (candidate.failed) return candidate
        if (candidate.stream != null && !candidate.isStreamExpired(nowEpochMs())) return candidate
        return try {
            val stream = withRetry { streaming.getStreamUrl(candidate) }
            candidate.copy(
                stream = stream,
                lastResolvedAtIso = Instant.ofEpochMilli(nowEpochMs()).toString(),
                failed = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            candidate.copy(stream = null, failed = true)
        }
    }

    /** Retries [block] up to [PlaybackResolverSettings.streamResolutionRetries] times with backoff. */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        val maxAttempts = settings.streamResolutionRetries.coerceAtLeast(1)
        var lastError: Throwable? = null
        for (attempt in 0 until maxAttempts) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxAttempts - 1) {
                    delay(settings.retryBaseDelayMs * (1L shl attempt))
                }
            }
        }
        throw lastError ?: IllegalStateException("Stream resolution failed")
    }

    /** A candidate is usable if it isn't failed and either isn't resolved yet or its stream is fresh. */
    private fun StreamCandidate.isUsable(nowMs: Long): Boolean =
        !failed && (stream == null || !isStreamExpired(nowMs))

    /** True when the resolved stream has expired (or its timestamp is missing/unparseable). */
    private fun StreamCandidate.isStreamExpired(nowMs: Long): Boolean {
        val resolvedAt = lastResolvedAtIso
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: return true
        return nowMs - resolvedAt >= settings.streamExpiryMs
    }
}
