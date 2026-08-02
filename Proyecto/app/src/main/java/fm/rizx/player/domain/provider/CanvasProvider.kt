package fm.rizx.player.domain.provider

import fm.rizx.player.domain.model.CanvasAspect
import fm.rizx.player.domain.model.CanvasCandidate
import fm.rizx.player.domain.model.CanvasQuality
import fm.rizx.player.domain.model.Track

/**
 * A source of animated cover art.
 *
 * **Not a [ProviderDescriptor], and deliberately outside the shared [ProviderRegistry].** That registry
 * is for the providers a user picks between — it tracks health, it is single-active per kind, and it is
 * what the Plugins screen lists. A canvas provider is none of those things: it is internal, it is tried
 * in priority order until one answers, and putting it in the registry would publish it on a screen where
 * it would only be confusing. `CanvasProviderRegistry` is a plain priority-ordered list instead.
 *
 * A provider must return an empty list rather than throw when it simply has nothing — "no video for this
 * song" is the common case, not a failure. Anything it does throw is caught by the registry, because a
 * broken canvas provider may never disturb playback.
 */
interface CanvasProvider {
    /** Stable id, shown in the diagnostics block. */
    val id: String

    /** Human-readable name for the same block. */
    val displayName: String

    /** Lower is tried first. Curated sources would sit below the search-based ones. */
    val priority: Int

    /**
     * The canvases this provider has for [track], **best first** — empty when it has none.
     *
     * A list rather than one answer because the player is the only part that finds out a stream doesn't
     * work, and by then re-resolving is a second round trip. Apple contributes its square and portrait
     * cuts; a provider with a single asset returns a single-element list.
     *
     * Empty is the *common* case, not a failure — "no video for this song" must never throw.
     *
     * [preferredAspect] is a hint — a provider with only one shape returns it regardless. [quality] caps
     * the resolution asked for, which is how the metered/unmetered decision reaches the wire.
     */
    suspend fun resolve(
        track: Track,
        preferredAspect: CanvasAspect,
        quality: CanvasQuality,
    ): List<CanvasCandidate>
}
