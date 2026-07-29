package fm.rizx.player.domain.provider

import fm.rizx.player.domain.model.Track

/**
 * A discovery provider: recommends tracks from recent listening context — upstream Nuclear's
 * `DiscoveryProvider` contract (`getRecommendations(context, {variety, limit})`), mirrored natively so
 * JS plugins and native engines share one seam. A [ProviderDescriptor] of kind
 * [ProviderKind.DISCOVERY]; the up-next selector lists these alongside the built-in engines.
 */
interface DiscoveryProvider : ProviderDescriptor {
    /**
     * Recommend tracks given [context] (recent queue tracks, oldest → newest; often just the seed).
     * [variety] 0.0 = stay close, 1.0 = explore. Empty on failure — callers fall back.
     */
    suspend fun getRecommendations(
        context: List<Track>,
        variety: Double = 0.5,
        limit: Int? = null,
    ): List<Track>
}
