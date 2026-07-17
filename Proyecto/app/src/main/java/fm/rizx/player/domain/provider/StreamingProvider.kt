package fm.rizx.player.domain.provider

import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.Track

/**
 * A streaming provider: finds playable sources for a [Track] and resolves concrete, **ephemeral**
 * stream URLs on demand. Implementations are [ProviderDescriptor]s of kind
 * [ProviderKind.STREAMING], so they live in the registry and are single-active.
 *
 * The upstream V1/V2 method pair is collapsed to a single Track-based signature (see
 * NUCLEAR_UPSTREAM_STUDY.md §3.8). Resolution is two-phase (§5): first [searchForTrack] discovers
 * candidates (matches are imperfect — usually several), then [getStreamUrl] resolves one to a
 * concrete [Stream] just before playback.
 */
interface StreamingProvider : ProviderDescriptor {

    /** Phase 1 — discover candidate sources for [track]. May return several imperfect matches. */
    suspend fun searchForTrack(track: Track): List<StreamCandidate>

    /** Phase 2 — resolve [candidate] to a concrete, ephemeral [Stream] URL. */
    suspend fun getStreamUrl(candidate: StreamCandidate): Stream
}
