package fm.rizx.player.data.canvas

import fm.rizx.player.domain.model.CanvasAspect
import fm.rizx.player.domain.model.CanvasCandidate
import fm.rizx.player.domain.model.CanvasQuality
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.CanvasProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** How long any one provider gets before the chain moves on. A canvas is decoration; it can't stall. */
private const val PROVIDER_TIMEOUT_MS = 12_000L

/**
 * The canvas providers, tried cheapest-and-most-trustworthy first.
 *
 * Priority order, first non-null wins — **not** a race like the lyrics chain. Lyrics race because five
 * sources hold the same words and the fastest is as good as any; canvases don't, because a curated loop
 * made for the song is strictly better than a music video found by search, and racing would let the
 * worse one win by being quicker.
 *
 * A provider that throws or hangs is skipped, not propagated. A broken canvas source must never disturb
 * playback — that rule is why this class swallows rather than rethrows.
 */
class CanvasProviderRegistry(
    providers: List<CanvasProvider>,
    private val providerTimeoutMs: Long = PROVIDER_TIMEOUT_MS,
) {
    private val ordered = providers.sortedBy { it.priority }

    /** The registered providers in the order they will be asked. */
    val providers: List<CanvasProvider> get() = ordered

    /**
     * The candidates from the first provider that offers any, best first — empty when none does.
     *
     * **It stops at the first provider that answers**, rather than collecting from all of them. Asking
     * YouTube anyway when Apple already handed over a purpose-made loop would spend a NewPipe extraction
     * (2-4 round trips) on a list nobody will reach. Falling across providers is the caller's move, via
     * [skip], and only after the winner's candidates have actually failed to play.
     *
     * [skip] holds provider ids not to ask — a source the user switched off, or one whose candidates
     * just failed. [onError] reports a provider that failed so the diagnostics can name it; the chain
     * continues either way. Cancellation of the *caller* still propagates — leaving the screen must stop
     * the work, and only a provider's own timeout is treated as a skip.
     */
    suspend fun resolve(
        track: Track,
        preferredAspect: CanvasAspect,
        quality: CanvasQuality,
        skip: Set<String> = emptySet(),
        onError: (CanvasProvider, Throwable) -> Unit = { _, _ -> },
    ): List<CanvasCandidate> {
        for (provider in ordered) {
            if (provider.id in skip) continue
            val candidates = try {
                withTimeout(providerTimeoutMs) { provider.resolve(track, preferredAspect, quality) }
            } catch (e: TimeoutCancellationException) {
                onError(provider, e)
                continue
            } catch (e: CancellationException) {
                throw e // the caller left the screen — stop, don't fall through to the next provider
            } catch (e: Exception) {
                onError(provider, e)
                continue
            }
            if (candidates.isNotEmpty()) return candidates
        }
        return emptyList()
    }
}
