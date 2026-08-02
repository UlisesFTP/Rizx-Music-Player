package fm.rizx.player.data.repository

import fm.rizx.player.data.provider.FakeStreamingProvider
import fm.rizx.player.data.provider.FakeStreamingProviderB
import fm.rizx.player.domain.lossless.LosslessIndexProvider
import fm.rizx.player.domain.model.DownloadFormat
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.EnabledProviderStore
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.provider.StreamingProvider
import fm.rizx.player.domain.repository.NoStreamingProviderException
import fm.rizx.player.domain.repository.StreamingRepository
import kotlinx.coroutines.CancellationException

/**
 * Resolves streams through a **fallback chain** of streaming providers (active first). A metadata-only
 * track (e.g. from Deezer) that the full-track provider (Audius) can't match therefore degrades to the
 * next real provider (e.g. iTunes 30-second previews) instead of hard-failing with a "source error".
 *
 * Phase-1 [searchForTrack] tries each usable provider in order and returns the first **non-empty**
 * candidate list; phase-2 [getStreamUrl] routes back to the exact provider that produced the candidate
 * (via [StreamCandidate.source]), so a candidate from the fallback provider resolves against it.
 *
 * Offline-demo providers ([FakeStreamingProvider]/[FakeStreamingProviderB]) never silently shadow a real
 * fallback — they return a placeholder stream for *any* track, so they participate only when one of them
 * is explicitly the active provider (the offline demo mode). Disabled providers are skipped, except the
 * active one, which is still tried first (then falls back) rather than dead-ending the kind.
 */
class StreamingRepositoryImpl(
    private val registry: ProviderRegistry,
    private val enabled: EnabledProviderStore,
) : StreamingRepository {

    override suspend fun searchForTrack(track: Track): List<StreamCandidate> {
        // A track natively owned by a streaming provider — a YouTube video, a SoundCloud permalink — must
        // resolve against THAT provider, playing the exact track the user picked, not be re-searched by
        // title on whichever provider is first in the chain. Metadata-only tracks (Deezer/iTunes) have no
        // streaming owner and fall through to the fallback chain, "find this song anywhere".
        (registry.get(track.source.provider, ProviderKind.STREAMING) as? StreamingProvider)?.let { owner ->
            val native = runCatching { owner.searchForTrack(track) }.getOrDefault(emptyList())
            if (native.isNotEmpty()) return native
        }
        val chain = streamingChain()
        if (chain.isEmpty()) throw NoStreamingProviderException()
        var lastError: Exception? = null
        var anyReturned = false
        for (provider in chain) {
            try {
                val candidates = provider.searchForTrack(track)
                anyReturned = true
                if (candidates.isNotEmpty()) return candidates
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e // a broken provider must not abort the chain
            }
        }
        // No provider matched. Rethrow only if *every* provider errored; otherwise it's a clean miss
        // (empty list → the resolver surfaces a "no stream" state without treating it as a crash).
        if (!anyReturned && lastError != null) throw lastError
        return emptyList()
    }

    override suspend fun getStreamUrl(candidate: StreamCandidate): Stream =
        providerFor(candidate).getStreamUrl(candidate)

    override suspend fun getDownloadStreamUrl(candidate: StreamCandidate, format: DownloadFormat): Stream =
        providerFor(candidate).getDownloadStreamUrl(candidate, format)

    /** The provider that produced [candidate], else the active one. */
    private fun providerFor(candidate: StreamCandidate): StreamingProvider =
        (registry.get(candidate.source.provider, ProviderKind.STREAMING) as? StreamingProvider)
            ?: (registry.activeDescriptor(ProviderKind.STREAMING) as? StreamingProvider)
            ?: throw NoStreamingProviderException()

    /**
     * The ordered fallback chain: the active provider first, then the other **real** enabled providers.
     * Demo providers are excluded unless active; if filtering leaves nothing, falls back to the full
     * active-first list so playback never dead-ends purely because of enable/disable flags.
     */
    private suspend fun streamingChain(): List<StreamingProvider> {
        val all = registry.list(ProviderKind.STREAMING)
            .filterIsInstance<StreamingProvider>()
            // A plugin whose only job is publishing a lossless index registers as a streaming provider —
            // it has to, that is the contract it implements — but it has no ordinary search to offer.
            // Leaving it in the chain would mean consulting a community index during *every* resolve,
            // including in Standard mode, which is exactly the extra request this feature promises never
            // to make. It is reached only through the resolver in `QueueStreamResolver`.
            .filterNot { it is LosslessIndexProvider && it.isLosslessOnly }
        if (all.isEmpty()) return emptyList()
        val activeId = registry.getActive(ProviderKind.STREAMING)
        val enabledState = enabled.snapshot(all.map { it.id })
        val ordered = all.sortedByDescending { it.id == activeId }
        return ordered.filter { p ->
            val isActive = p.id == activeId
            val isEnabled = enabledState[p.id] != false
            isActive || (isEnabled && p.id !in DEMO_PROVIDER_IDS)
        }.ifEmpty { ordered }
    }

    private companion object {
        val DEMO_PROVIDER_IDS = setOf(FakeStreamingProvider.ID, FakeStreamingProviderB.ID)
    }
}
