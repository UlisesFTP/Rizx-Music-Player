package fm.rizx.player.data.lossless

import fm.rizx.player.domain.lossless.LosslessIndexItem
import fm.rizx.player.domain.lossless.LosslessIndexProvider
import fm.rizx.player.domain.lossless.LosslessIndexSource
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
import kotlinx.coroutines.CancellationException

/**
 * The index, supplied by an installed plugin rather than by this app.
 *
 * **This is the whole reason the feature is shaped this way.** Rizx is a public, AGPL repository, and a
 * hard-coded pointer to a third party's pile of commercial FLACs would make it a distributor of that
 * pile rather than a player that can read an index. So the repository ships everything that is generic
 * and verifiable — the matching, the header check, the playback, the fallback — and the list of URLs
 * arrives in a plugin the user chose to install. Point one at your own server and it works the same way.
 *
 * The split is also forced by the runtime: the sandbox's `fetch` returns UTF-8 text only, so a plugin
 * physically cannot read a FLAC header. It fetches JSON; the verification stays native.
 *
 * A broken plugin fails alone, as every provider must — its exception is swallowed here and the other
 * indexes (and then the ordinary stream) carry on.
 */
class PluginLosslessIndexSource(
    private val registry: ProviderRegistry,
) : LosslessIndexSource {

    override fun isAvailable(): Boolean = indexProviders().isNotEmpty()

    override suspend fun lookup(track: Track): List<LosslessIndexItem> {
        val providers = indexProviders()
        if (providers.isEmpty()) return emptyList()

        val rows = mutableListOf<LosslessIndexItem>()
        for (provider in providers) {
            try {
                rows += provider.losslessLookup(track)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // One plugin timing out, throwing, or returning nonsense must not cost the others their
                // turn — and must never reach playback, which carries on with the ordinary stream.
            }
        }
        // Same URL from two indexes is one candidate; the matcher would otherwise score it twice and
        // then call it an ambiguous tie with itself.
        return rows.distinctBy { it.url }
    }

    /**
     * Every enabled provider that publishes an index.
     *
     * Read from the registry on each call rather than captured once: plugins are installed, enabled and
     * quarantined while the app is running, and this is what makes the Settings row light up the moment
     * one is added.
     */
    private fun indexProviders(): List<LosslessIndexProvider> =
        registry.list(ProviderKind.STREAMING)
            .filterIsInstance<LosslessIndexProvider>()
            .filter { it.hasLosslessIndex }
}
