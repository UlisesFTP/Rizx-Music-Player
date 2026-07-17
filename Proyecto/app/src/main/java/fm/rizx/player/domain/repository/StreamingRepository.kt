package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.Track

/**
 * Entry point for streaming operations. Routes to the **active** streaming provider chosen in the
 * registry; the resolver depends on this, never on a provider implementation directly. Throws
 * [NoStreamingProviderException] when no streaming provider is active.
 */
interface StreamingRepository {

    /** Phase 1 — discover candidate sources for [track] via the active streaming provider. */
    suspend fun searchForTrack(track: Track): List<StreamCandidate>

    /** Phase 2 — resolve [candidate] to a concrete, ephemeral [Stream] via the active provider. */
    suspend fun getStreamUrl(candidate: StreamCandidate): Stream
}

class NoStreamingProviderException : Exception("No streaming provider is active")
