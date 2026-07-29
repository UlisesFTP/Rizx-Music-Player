package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtistRef
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

/** A credited artist as the player shows it: the name on the line, and the page a tap opens. */
data class LinkedArtist(val name: String, val source: ProviderRef? = null)

/**
 * Turns a track's billing line into artist pages the app can actually open — one per artist.
 *
 * Two things go wrong without it, both seen live:
 *
 * **The page belongs to a duplicate.** Catalogues carry several entries under one artist's name: the
 * real one, plus the thin ones a distributor minted for a feature credit. Deezer has a 27-follower
 * "The Weeknd" with no albums next to the 14.6M one — and returns it **first**. Taking search rank 1,
 * or trusting whichever id a track happens to credit, lands on a profile with three songs on it. So
 * candidates are filtered to the same artist by name and then **ranked by how complete the profile
 * is** ([ArtistRef.followers]); with no such signal (a provider that doesn't publish one) the ranking
 * is a no-op and search order stands.
 *
 * A track's *own* artist id is still the better answer in general — it came from the catalogue that
 * knows the song — so it is only overridden when the search shows it **losing to a same-name entry it
 * appears alongside**. Competing in one result set is the evidence that they are two rows for one
 * artist rather than two artists who share a name.
 *
 * **The second artist is unreachable.** A YouTube-sourced track carries one credit holding the whole
 * billing ("Omar Courtz & De La Rose"), so tapping it could only ever open one page — and usually
 * none, since no catalogue has an artist by that joined name. [ArtistNameMatching.credits] proposes a
 * split, but a split is only *taken* when every part resolves and the **weakest part outranks the
 * whole**. That comparison is what keeps "Earth, Wind & Fire" (1.2M followers, against a "Wind" with
 * 1.7K) and "Simon & Garfunkel" (whose "Garfunkel" resolves to nobody) intact.
 *
 * Answers are memoized per name for the process — misses included, because a lookup that failed once
 * fails the same way for every track by that artist, and the player asks on every track change.
 */
class ResolveTrackArtistsUseCase(
    private val metadata: MetadataRepository,
    private val registry: ProviderRegistry,
) {

    private val lock = Mutex()
    private val memo = LinkedHashMap<String, List<ArtistRef>>()

    suspend operator fun invoke(track: Track?): List<LinkedArtist> {
        val credits = track?.artists.orEmpty().filter { it.name.isNotBlank() }
        if (credits.isEmpty()) return emptyList()
        // No metadata provider, no page to open — the names still render, just untappable.
        val active = registry.activeDescriptor(ProviderKind.METADATA)?.id
            ?: return credits.map { LinkedArtist(it.name) }

        // The provider already split the billing for us; nothing to guess.
        if (credits.size > 1) return credits.map { resolve(it, active).linked }

        val credit = credits.single()
        val whole = resolve(credit, active)
        val parts = ArtistNameMatching.credits(credit.name)
        if (parts.size < 2) return listOf(whole.linked)

        // Every part has to name a real artist, or this was never a collaboration.
        val split = parts.map { part -> byName(part, active) ?: return listOf(whole.linked) }
        // Strictly greater, so "no signal anywhere" (all zero) leaves the billing as it was written.
        return if (split.minOf { it.followers } > whole.followers) {
            split.map { it.linked }
        } else {
            listOf(whole.linked)
        }
    }

    /** A credit resolved against the catalogue, keeping the follower count the choice was made on. */
    private class Resolved(val name: String, val source: ProviderRef?, val followers: Long) {
        val linked get() = LinkedArtist(name, source)
    }

    private suspend fun resolve(credit: ArtistCredit, active: String): Resolved {
        val candidates = candidates(credit.name, active)
        val best = candidates.firstOrNull()
        val own = credit.source?.takeIf { it.provider == active }
            ?: return Resolved(credit.name, best?.source, best.followerCount)

        val incumbent = candidates.firstOrNull { it.source == own }
        // Overridden only when the catalogue shows the credited id losing to a same-name row it was
        // returned next to. Not found in the results ⇒ no evidence they are the same artist ⇒ kept.
        return if (incumbent != null && best != null && best.source != own) {
            Resolved(credit.name, best.source, best.followerCount)
        } else {
            Resolved(credit.name, own, incumbent.followerCount)
        }
    }

    /** The best profile for a bare name, or null when nothing in the catalogue is that artist. */
    private suspend fun byName(name: String, active: String): Resolved? {
        val best = candidates(name, active).firstOrNull() ?: return null
        return Resolved(name, best.source, best.followerCount)
    }

    /**
     * Every catalogue row that really is this artist, most complete first.
     *
     * De-channelized name first, raw name second — Deezer answers *nothing* for "ModjoOfficial" and
     * answers "Modjo" for "modjo". Anything that goes wrong is "no candidates", never an error.
     */
    private suspend fun candidates(name: String, active: String): List<ArtistRef> {
        // Keyed on the folded spellings, not the raw name, so "Modjo" and "MODJO" share one answer.
        val key = "$active|" + ArtistNameMatching.keys(name).sorted().joinToString("|")
        lock.withLock { memo[key] }?.let { return it }

        var found = emptyList<ArtistRef>()
        for (query in ArtistNameMatching.queries(name)) {
            val hits = try {
                metadata
                    .search(SearchParams(query = query, types = listOf(SearchCategory.ARTISTS), limit = SEARCH_LIMIT))
                    .artists
                    .filter { ArtistNameMatching.sameArtist(it.name, name) }
                    // Stable, so a provider with no follower count keeps its own ranking.
                    .sortedByDescending { it.followers ?: -1L }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (hits.isNotEmpty()) {
                found = hits
                break
            }
        }
        lock.withLock {
            memo[key] = found
            if (memo.size > MAX_MEMO) memo.keys.firstOrNull()?.let(memo::remove)
        }
        return found
    }

    private companion object {
        /** Deep enough that the real artist is still in the page when duplicates rank above them. */
        const val SEARCH_LIMIT = 10
        const val MAX_MEMO = 200
    }
}

/** Unknown popularity counts as none, so an unranked row never wins a comparison by default. */
private val ArtistRef?.followerCount: Long get() = this?.followers ?: 0L
