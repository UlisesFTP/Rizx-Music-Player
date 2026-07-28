package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchCategory
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.repository.MetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Finds an artist page the app can actually open for [track]'s primary artist.
 *
 * A `ProviderRef` is only meaningful to the provider that minted it, and the artist screen loads through
 * the **active metadata provider** (Deezer). So:
 *
 *  - A Deezer-sourced song already credits a Deezer artist id → that ref is used as-is.
 *  - A song from anywhere else — a YouTube Mix credits an uploader *name* and nothing more, iTunes mints
 *    an `itunes:artist:…` the Deezer screen cannot load — is looked up **by name** on the active
 *    provider. That is the "find its Deezer equivalent" step.
 *  - No match, or a match whose name isn't really the same artist, resolves to `null`: the caller then
 *    leaves the name untappable rather than opening a page about someone else.
 *
 * Names go through [ArtistNameMatching], which folds accents/case like the feed's dedup does and reads a
 * YouTube *channel* name as the artist behind it — "ModjoOfficial" is searched, and matched, as "Modjo".
 * "Bad Bunny" still matches "BAD BUNNY" and still doesn't match "Bad Bunny Tribute Band".
 *
 * Answers are memoized per name for the process — including misses, because a lookup that failed once
 * will fail the same way for every track by that artist in this queue, and the player asks on every
 * track change.
 */
class ResolveArtistRefUseCase(
    private val metadata: MetadataRepository,
    private val registry: ProviderRegistry,
) {

    private val lock = Mutex()
    private val memo = LinkedHashMap<String, ProviderRef?>()

    suspend operator fun invoke(track: Track?): ProviderRef? {
        val credit = track?.artists?.firstOrNull() ?: return null
        val active = registry.activeDescriptor(ProviderKind.METADATA)?.id ?: return null

        // Already the right provider's own id — nothing to resolve.
        credit.source?.takeIf { it.provider == active }?.let { return it }

        val name = credit.name.trim().takeIf { it.isNotEmpty() } ?: return null
        // Keyed on the folded spellings, not the raw name, so "Modjo" and "MODJO" share one answer.
        val key = "$active|" + ArtistNameMatching.keys(name).sorted().joinToString("|")
        lock.withLock { if (memo.containsKey(key)) return memo[key] }

        val resolved = search(name)
        lock.withLock {
            memo[key] = resolved
            if (memo.size > MAX_MEMO) memo.keys.firstOrNull()?.let(memo::remove)
        }
        return resolved
    }

    /**
     * Searches for the artist, de-channelized name first. Two queries at most, and only when the first
     * one comes back without a convincing match — the point is that Deezer answers *nothing* for
     * "ModjoOfficial" and answers "Modjo" for "modjo".
     *
     * Anything that goes wrong is "no equivalent", never an error.
     */
    private suspend fun search(name: String): ProviderRef? {
        for (query in ArtistNameMatching.queries(name)) {
            val hit = try {
                metadata
                    .search(SearchParams(query = query, types = listOf(SearchCategory.ARTISTS), limit = SEARCH_LIMIT))
                    .artists
                    .firstOrNull { ArtistNameMatching.sameArtist(it.name, name) }
                    ?.source
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (hit != null) return hit
        }
        return null
    }

    private companion object {
        /** A handful, so a near-miss spelling still has a chance of being in the page. */
        const val SEARCH_LIMIT = 5
        const val MAX_MEMO = 200
    }
}
